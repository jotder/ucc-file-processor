package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
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
