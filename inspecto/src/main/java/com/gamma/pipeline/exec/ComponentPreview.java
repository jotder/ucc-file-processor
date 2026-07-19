package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>T18 — per-component dry-run / preview (validate + bounded sample, scratch-only).</b> Runs a
 * {@code transform.*} node over a handful of sample rows and returns the produced named relations, so the
 * UI can "test a component in isolation" (doc §7.2). It reuses the <em>production</em> row-shaping logic
 * ({@link RowShaper}) — no divergent test path — executed against a throwaway embedded DuckDB seeded from
 * the sample; the scratch database is deleted afterwards, so it never touches any real output.
 *
 * <p>Single-input transforms only ({@code filter}/{@code validate}/{@code route}/{@code dedup}/{@code split}/
 * {@code map}/{@code select}/{@code derive}); {@code transform.merge} is multi-input and not previewable here.
 * Sample values are seeded as {@code VARCHAR} columns (the union of the rows' keys); operator predicates cast
 * as needed, exactly as in production.
 */
@PublicApi(since = "4.3.0")
public final class ComponentPreview {

    private ComponentPreview() {}

    /** The maximum rows returned per produced relation (a preview is bounded). */
    public static final int MAX_ROWS = 1000;

    private static final String INPUT = "preview_input";

    /** One produced relation in a preview: the {@link com.gamma.pipeline.PipelineRel} and the sampled output rows. */
    public record RelationPreview(String rel, int rowCount, List<Map<String, Object>> rows) {}

    /** The preview outcome: the input column set + every relation the node produced over the sample. */
    public record Result(List<String> inputColumns, List<RelationPreview> relations) {}

    /**
     * Preview {@code node} (a {@code transform.*} node) over {@code sampleRows}. Throws
     * {@link IllegalArgumentException} for an empty sample or a non-previewable node type.
     */
    public static Result transform(PipelineNode node, List<Map<String, Object>> sampleRows)
            throws SQLException, java.io.IOException {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        List<String> columns = ScratchTables.columnsOf(sampleRows);
        if (columns.isEmpty()) throw new IllegalArgumentException("sample rows have no columns");

        File db = DuckDbUtil.tempDbFile("preview_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, INPUT, columns, sampleRows);
            List<RowShaper.Relation> produced = RowShaper.shape(conn, node, INPUT, "preview_" + node.id());
            List<RelationPreview> out = new ArrayList<>();
            for (RowShaper.Relation r : produced) {
                out.add(new RelationPreview(r.rel(),
                        ScratchTables.count(conn, r.table()),
                        ScratchTables.readRows(conn, r.table(), MAX_ROWS)));
            }
            return new Result(columns, out);
        } finally {
            DuckDbUtil.deleteTempDb(db);   // throwaway scratch DB
        }
    }

    // ── grammar (parse raw sample text via DuckDB read_csv) ────────────────────────

    /** A grammar parse preview: the columns the dialect produced, the parsed rows, and the reject count. */
    public record GrammarResult(List<String> columns, int rowCount, List<Map<String, Object>> rows,
                                int rejectedRows) {}

    /**
     * Preview a {@code grammar} component over raw {@code sampleText}: parse it with the grammar's CSV dialect
     * (delimiter / header / skip / quote / escape / encoding) using the <em>production</em> {@code read_csv}
     * reader on a throwaway DuckDB, then read the parsed columns/rows back. Mirrors how a parser node reads a
     * landed file (doc §7.2). Throws {@link IllegalArgumentException} for empty input.
     */
    public static GrammarResult grammar(Map<String, Object> content, String sampleText)
            throws SQLException, java.io.IOException {
        if (sampleText == null || sampleText.isBlank())
            throw new IllegalArgumentException("sample text is required");

        File db = DuckDbUtil.tempDbFile("preview_");
        java.nio.file.Path sample = java.nio.file.Files.createTempFile("preview_grammar_", ".csv");
        try {
            java.nio.file.Files.writeString(sample, sampleText);
            String path = sample.toAbsolutePath().toString().replace("\\", "/");

            StringBuilder opts = new StringBuilder();
            opts.append("delim=").append(ScratchTables.sqlStr(strOr(content, "delimiter", ",")));
            opts.append(", header=").append(boolOr(content, "has_header", false));
            opts.append(", skip=").append(intOr(content, "skip_header_lines", 0));
            String quote = strOrNull(content, "quote");
            if (quote != null) opts.append(", quote=").append(ScratchTables.sqlStr(quote));
            String escape = strOrNull(content, "escape");
            if (escape != null) opts.append(", escape=").append(ScratchTables.sqlStr(escape));
            String enc = strOrNull(content, "encoding");
            if (enc != null) opts.append(", encoding=").append(ScratchTables.sqlStr(enc));
            opts.append(", auto_detect=true, ignore_errors=true, store_rejects=true");

            try (Connection conn = DuckDbUtil.openConnection(db)) {
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE preview_parsed AS SELECT * FROM read_csv("
                            + ScratchTables.sqlStr(path) + ", " + opts + ")");
                }
                return new GrammarResult(
                        ScratchTables.columnNames(conn, "preview_parsed"),
                        ScratchTables.count(conn, "preview_parsed"),
                        ScratchTables.readRows(conn, "preview_parsed", MAX_ROWS),
                        rejectCount(conn));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(sample);
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── pipeline parsing frontend (parse raw sample text with a draft's parsing: settings) ──

    /**
     * Preview a pipeline draft's <b>parsing frontend</b> over raw {@code sampleText} (stream
     * onboarding, v5.1.0): write the sample to a scratch file and read it with the same DuckDB
     * idioms {@link com.gamma.etl.DuckDbCsvIngester} uses per frontend — delimited
     * {@code read_csv} with the draft's dialect, fixed-width {@code substring} slices,
     * {@code read_json}, or {@code regexp_extract} named groups. Schema-less by design: this
     * is the parse step, before names/types are attached — delimited/json columns come from
     * the header/keys (auto-detected), fixed-width slices and regex groups project under their
     * own declared names. Plugin ingesters and binary fixed-width records cannot run over
     * pasted text and are rejected with {@link IllegalArgumentException}.
     */
    public static GrammarResult parsing(PipelineConfig cfg, String sampleText)
            throws SQLException, java.io.IOException {
        if (sampleText == null || sampleText.isBlank())
            throw new IllegalArgumentException("sample text is required");
        if (cfg.fixedWidth() != null && cfg.fixedWidth().binary())
            throw new IllegalArgumentException(
                    "binary fixed-width records cannot be previewed from pasted text");
        if (cfg.fixedWidth() == null && cfg.json() == null && cfg.textRegex() == null
                && cfg.schemas().ingesterClass() != null)
            throw new IllegalArgumentException("parsing preview is not supported for the plugin frontend ("
                    + cfg.schemas().ingesterClass() + ") — run the pipeline against a real file instead");

        File db = DuckDbUtil.tempDbFile("preview_");
        java.nio.file.Path sample = java.nio.file.Files.createTempFile("preview_parsing_", ".txt");
        try {
            java.nio.file.Files.writeString(sample, sampleText);
            String path = sample.toAbsolutePath().toString().replace("\\", "/");
            try (Connection conn = DuckDbUtil.openConnection(db)) {
                if (cfg.json() != null && cfg.json().newlineDelimited())
                    return ndjsonPreview(conn, cfg, path, sampleText);
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE preview_parsed AS " + parsingSelect(cfg, path));
                }
                return new GrammarResult(
                        ScratchTables.columnNames(conn, "preview_parsed"),
                        ScratchTables.count(conn, "preview_parsed"),
                        ScratchTables.readRows(conn, "preview_parsed", MAX_ROWS),
                        rejectCount(conn));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(sample);
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * The frontend-specific {@code SELECT} reading {@code path} — the same read specs
     * {@code DuckDbCsvIngester} builds, minus the schema projection (which does not exist yet
     * at the parsing stage). NDJSON is handled separately ({@link #ndjsonPreview}).
     */
    private static String parsingSelect(PipelineConfig cfg, String path) {
        if (cfg.fixedWidth() != null) return fixedWidthSelect(cfg, path);
        if (cfg.textRegex() != null)  return textRegexSelect(cfg, path);
        if (cfg.json() != null)       return jsonSelect(cfg, path);
        return delimitedSelect(cfg, path);
    }

    /**
     * NDJSON preview, mirroring the engine's newline path exactly: the single-column line
     * reader (header-skip semantics included), a {@code json_valid} filter — a malformed line
     * is routed away, never null-padded — then {@code json_extract_string} per top-level key.
     * Keys are discovered from the valid records in first-seen order (schema-less: the keys
     * ARE the columns). {@code rejectedRows} counts the dropped invalid lines.
     */
    private static GrammarResult ndjsonPreview(Connection conn, PipelineConfig cfg,
                                               String path, String sampleText) throws SQLException {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE js_lines AS SELECT \"line\" FROM " + lineReader(cfg, path)
                    + " WHERE json_valid(\"line\")");
        }
        int skip = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        long totalAfterSkip = Math.max(0, sampleText.lines().count() - skip);
        int valid = ScratchTables.count(conn, "js_lines");
        int invalid = (int) Math.max(0, totalAfterSkip - valid);

        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT json_keys(\"line\") FROM js_lines")) {
            while (rs.next()) {
                java.sql.Array arr = rs.getArray(1);
                if (arr != null) for (Object k : (Object[]) arr.getArray()) keys.add(String.valueOf(k));
            }
        }
        if (keys.isEmpty())
            return new GrammarResult(List.of(), 0, List.of(), invalid + rejectCount(conn));

        StringBuilder proj = new StringBuilder();
        boolean first = true;
        for (String k : keys) {
            if (!first) proj.append(", ");
            first = false;
            proj.append("json_extract_string(\"line\", '$.\"").append(k.replace("'", "''"))
                .append("\"') AS ").append(quoteIdent(k));
        }
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE preview_parsed AS SELECT " + proj + " FROM js_lines");
        }
        return new GrammarResult(
                ScratchTables.columnNames(conn, "preview_parsed"),
                ScratchTables.count(conn, "preview_parsed"),
                ScratchTables.readRows(conn, "preview_parsed", MAX_ROWS),
                invalid + rejectCount(conn));
    }

    private static String delimitedSelect(PipelineConfig cfg, String path) {
        String delim = (cfg.csv().delimiter() == null || cfg.csv().delimiter().isEmpty())
                ? "," : cfg.csv().delimiter();
        // all_varchar mirrors production raw ingest (100% VARCHAR columns) AND keeps the preview
        // rows JSON-serializable — auto-detect would otherwise type a date column as a DuckDB
        // DATE, which the parsed→typed hop is precisely meant to make explicit, not implicit.
        return "SELECT * FROM read_csv(" + ScratchTables.sqlStr(path)
                + ", delim=" + ScratchTables.sqlStr(delim)
                + ", header=" + cfg.csv().hasHeader()
                + ", skip=" + cfg.csv().skipHeaderLines()
                + ", auto_detect=true, all_varchar=true, ignore_errors=true, store_rejects=true)";
    }

    /** The engine's single-column line reader: each physical line intact as VARCHAR {@code line}. */
    private static String lineReader(PipelineConfig cfg, String path) {
        int skip = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        return "read_csv(" + ScratchTables.sqlStr(path)
                + ", columns={'line':'VARCHAR'}, delim='', quote='', escape=''"
                + ", header=false, skip=" + skip
                + ", ignore_errors=true, null_padding=true, auto_detect=false, store_rejects=true)";
    }

    private static String fixedWidthSelect(PipelineConfig cfg, String path) {
        PipelineConfig.FixedWidth fw = cfg.fixedWidth();
        List<PipelineConfig.FixedWidth.Slice> slices = fw.slices();
        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < slices.size(); i++) {
            PipelineConfig.FixedWidth.Slice s = slices.get(i);
            String name = (s.name() == null || s.name().isBlank()) ? "field_" + i : s.name();
            if (i > 0) proj.append(", ");
            proj.append(trimmed(fw.trim(), "substring(\"line\", " + (s.start() + 1) + ", " + s.length() + ")"))
                .append(" AS ").append(quoteIdent(name));
        }
        return "SELECT " + proj + " FROM (SELECT \"line\" FROM " + lineReader(cfg, path)
                + " WHERE length(\"line\") >= " + fw.minRecordLength() + ") AS fw";
    }

    private static String textRegexSelect(PipelineConfig cfg, String path) {
        PipelineConfig.TextRegex tr = cfg.textRegex();
        StringBuilder names = new StringBuilder("[");
        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < tr.groupNames().size(); i++) {
            String g = tr.groupNames().get(i);
            if (i > 0) { names.append(", "); proj.append(", "); }
            names.append(ScratchTables.sqlStr(g));
            proj.append("rec[").append(ScratchTables.sqlStr(g)).append("] AS ").append(quoteIdent(g));
        }
        names.append(']');
        return "SELECT " + proj + " FROM (SELECT regexp_extract(\"line\", "
                + ScratchTables.sqlStr(tr.pattern()) + ", " + names + ") AS rec FROM "
                + lineReader(cfg, path)
                + " WHERE regexp_matches(\"line\", " + ScratchTables.sqlStr(tr.pattern()) + ")) AS tr";
    }

    /** {@code format: array | auto} only ({@code newline} goes through {@link #ndjsonPreview}).
     *  Like the engine's {@code read_json}, a malformed document fails the whole file. */
    private static String jsonSelect(PipelineConfig cfg, String path) {
        String format = "array".equals(cfg.json().format()) ? "array" : "auto";
        // Cast every column to VARCHAR (the array/auto counterpart of the NDJSON path's json_extract_string)
        // so an auto-detected timestamp comes back as its raw string, not a DuckDB TIMESTAMP → java.time —
        // keeping this preview byte-consistent with every other format. read_json has no all_varchar option,
        // so COLUMNS(*)::VARCHAR does the blanket cast without needing to know the column names. The
        // parsed→typed hop downstream is where typing becomes explicit, not this raw preview.
        return "SELECT COLUMNS(*)::VARCHAR FROM read_json(" + ScratchTables.sqlStr(path)
                + ", format='" + format + "')";
    }

    /** Quote a projection identifier, escaping embedded quotes. */
    private static String quoteIdent(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Wrap an expression in the configured fixed-width trim function (mirrors the ingester). */
    private static String trimmed(PipelineConfig.FixedWidth.Trim trim, String inner) {
        return switch (trim) {
            case NONE  -> inner;
            case LEFT  -> "ltrim(" + inner + ")";
            case RIGHT -> "rtrim(" + inner + ")";
            case BOTH  -> "trim(" + inner + ")";
        };
    }

    // ── schema (TRY_CAST each field to its declared type; split data / rejected) ────

    /**
     * Preview a {@code schema} component over {@code sampleRows} (all VARCHAR): {@code TRY_CAST} each declared
     * field to its type and split rows into {@code data} (every typed field casts, or is null/blank) and
     * {@code rejected} (some non-blank value fails its cast) — the same data-vs-reject split production applies
     * (doc §7.2). Throws {@link IllegalArgumentException} for an empty sample or a schema with no typed fields.
     */
    public static Result schema(Map<String, Object> content, List<Map<String, Object>> sampleRows)
            throws SQLException, java.io.IOException {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        List<String> columns = ScratchTables.columnsOf(sampleRows);
        if (columns.isEmpty()) throw new IllegalArgumentException("sample rows have no columns");
        List<Map<String, Object>> fields = schemaFields(content);
        if (fields.isEmpty())
            throw new IllegalArgumentException("schema has no typed fields (expected 'raw.fields' / 'fields' / 'columns')");

        List<String> conds = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String name = String.valueOf(f.get("name"));
            String type = f.get("type") == null ? null : f.get("type").toString();
            if (name == null || name.isBlank() || !columns.contains(name) || type == null) continue;
            String castOk = castExpr(name, type, f.get("format"));
            if (castOk == null) continue;   // VARCHAR / unknown → never rejects
            conds.add("((" + ScratchTables.q(name) + " IS NULL OR " + ScratchTables.q(name) + " = '') OR (" + castOk + "))");
        }
        String allOk = conds.isEmpty() ? "TRUE" : String.join(" AND ", conds);

        File db = DuckDbUtil.tempDbFile("preview_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, INPUT, columns, sampleRows);
            String data = "preview_schema__data";
            String rejected = "preview_schema__rejected";
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE " + ScratchTables.q(data) + " AS SELECT * FROM " + ScratchTables.q(INPUT)
                        + " WHERE COALESCE((" + allOk + "), FALSE)");
                st.execute("CREATE TABLE " + ScratchTables.q(rejected) + " AS SELECT * FROM " + ScratchTables.q(INPUT)
                        + " WHERE NOT COALESCE((" + allOk + "), FALSE)");
            }
            List<RelationPreview> out = List.of(
                    new RelationPreview("data", ScratchTables.count(conn, data),
                            ScratchTables.readRows(conn, data, MAX_ROWS)),
                    new RelationPreview("rejected", ScratchTables.count(conn, rejected),
                            ScratchTables.readRows(conn, rejected, MAX_ROWS)));
            return new Result(columns, out);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── sink (scratch-validate config against the sample — no write) ────────────────

    /** A sink preview: the bound store, the rows that would be written (bounded sample), and any warnings. */
    public record SinkResult(String store, int rowCount, List<Map<String, Object>> rows, List<String> warnings) {}

    /**
     * Scratch-validate a {@code sink} component against {@code sampleRows}: confirm it declares a {@code store},
     * its {@code format} is recognised, and any declared partition columns are present in the sample — reporting
     * the row count + bounded sample that <em>would</em> be written. Pure validation; nothing is persisted
     * (doc §7.2).
     */
    public static SinkResult sink(Map<String, Object> content, List<Map<String, Object>> sampleRows) {
        List<Map<String, Object>> rows = sampleRows == null ? List.of() : sampleRows;
        List<String> columns = ScratchTables.columnsOf(rows);
        List<String> warnings = new ArrayList<>();

        String store = strOrNull(content, "store");
        if (store == null) warnings.add("sink declares no 'store' name");

        String format = strOrNull(content, "format");
        if (format != null && !ALLOWED_SINK_FORMATS.contains(format.toLowerCase()))
            warnings.add("unrecognised format '" + format + "' (expected one of " + ALLOWED_SINK_FORMATS + ")");

        for (String pc : partitionColumns(content))
            if (!columns.contains(pc))
                warnings.add("partition column '" + pc + "' is not present in the sample rows");

        int cap = Math.min(rows.size(), MAX_ROWS);
        return new SinkResult(store, rows.size(), new ArrayList<>(rows.subList(0, cap)), warnings);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static final Set<String> ALLOWED_SINK_FORMATS = Set.of("parquet", "csv", "json", "avro");

    /** Count rows the grammar's {@code read_csv} rejected (0 if {@code store_rejects} never fired). */
    private static int rejectCount(Connection conn) {
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM reject_errors")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException noRejects) {
            return 0;   // reject tables only exist once store_rejects has fired
        }
    }

    /** The typed field list of a schema component: {@code raw.fields} (parse schema) or {@code fields}/{@code columns}. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> schemaFields(Map<String, Object> content) {
        if (content.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> rf)
            return castFields(rf);
        if (content.get("fields") instanceof List<?> f)   return castFields(f);
        if (content.get("columns") instanceof List<?> c)  return castFields(c);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castFields(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /**
     * A boolean "this value casts to {@code type}" SQL expression over column {@code name}, or {@code null} for
     * string/unknown types (which never reject). Date/timestamp with a {@code format} use {@code TRY_STRPTIME}.
     */
    private static String castExpr(String name, String type, Object format) {
        String col = ScratchTables.q(name);
        String t = type.trim().toLowerCase();
        String fmt = format == null ? null : format.toString();
        return switch (t) {
            case "int", "integer", "int32"            -> tryCast(col, "INTEGER");
            case "long", "bigint", "int64"            -> tryCast(col, "BIGINT");
            case "short", "smallint"                  -> tryCast(col, "SMALLINT");
            case "double", "float", "real", "decimal", "numeric" -> tryCast(col, "DOUBLE");
            case "bool", "boolean"                    -> tryCast(col, "BOOLEAN");
            case "date"      -> fmt != null ? "TRY_STRPTIME(" + col + ", " + ScratchTables.sqlStr(fmt) + ") IS NOT NULL"
                                            : tryCast(col, "DATE");
            case "timestamp", "datetime" -> fmt != null ? "TRY_STRPTIME(" + col + ", " + ScratchTables.sqlStr(fmt) + ") IS NOT NULL"
                                            : tryCast(col, "TIMESTAMP");
            default -> null;   // varchar / string / text / unknown — always valid
        };
    }

    private static String tryCast(String col, String sqlType) {
        return "TRY_CAST(" + col + " AS " + sqlType + ") IS NOT NULL";
    }

    /** Declared partition columns of a sink: a {@code partitions} list of names or {@code {column: …}} maps. */
    private static List<String> partitionColumns(Map<String, Object> content) {
        List<String> out = new ArrayList<>();
        if (content.get("partitions") instanceof List<?> parts) {
            for (Object o : parts) {
                if (o instanceof Map<?, ?> m && m.get("column") != null) out.add(m.get("column").toString());
                else if (o != null) out.add(o.toString());
            }
        }
        return out;
    }

    private static String strOr(Map<String, Object> m, String key, String dflt) {
        String v = strOrNull(m, key);
        return v == null ? dflt : v;
    }

    private static String strOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static boolean boolOr(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        return v == null ? dflt : Boolean.parseBoolean(v.toString());
    }

    private static int intOr(Map<String, Object> m, String key, int dflt) {
        Object v = m.get(key);
        if (v == null) return dflt;
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
