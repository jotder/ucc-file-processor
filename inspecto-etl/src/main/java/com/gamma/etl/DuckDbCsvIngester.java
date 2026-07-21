package com.gamma.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Native, vectorized CSV ingester that delegates parsing to DuckDB's built-in
 * {@code read_csv} reader instead of parsing line-by-line in Java.
 *
 * <h3>Why this exists</h3>
 * The Java {@link CsvIngester} parses each line with univocity and pushes cells
 * one at a time through the DuckDB appender (one JNI crossing per cell). On a
 * 2M-row × 12-col file that's ~129K rows/s and scales linearly with column
 * count — the dominant cost in the whole pipeline (see {@code docs/performance.md}).
 * DuckDB's {@code read_csv} is multi-threaded, vectorized, and SIMD-accelerated;
 * it reads the same file at 1M+ rows/s. Bringing DuckDB in for performance and
 * then bottlenecking on a Java parse loop defeated the purpose; this class fixes
 * that for well-formed files.
 *
 * <h3>Semantics &amp; how they map to the Java path</h3>
 * Empirically verified against DuckDB 1.5.2:
 * <ul>
 *   <li><b>All columns VARCHAR</b> — explicit {@code columns={c0:VARCHAR,…}} of
 *       width {@code maxSelector+1}. Matches the Java path (everything VARCHAR;
 *       {@link DataTransformer} casts later).</li>
 *   <li><b>Leading skip</b> — {@code skip = skip_header_lines + (has_header?1:0)}.</li>
 *   <li><b>Short rows / footers / blank lines</b> — rejected via
 *       {@code ignore_errors=true, null_padding=false}. A {@code "N rows selected."}
 *       footer or a banner line has the wrong column count and is dropped, exactly
 *       as the Java path's "insufficient columns" rejection would.</li>
 *   <li><b>Rejected rows</b> — captured by {@code store_rejects=true} into the
 *       connection's {@code reject_errors} table and written to
 *       {@code errors/&lt;base&gt;_errors.csv}, mirroring the Java path's error CSV
 *       (with richer per-column reasons).</li>
 *   <li><b>Selectors</b> — the projection is {@code SELECT "c&lt;selector&gt;" AS "name"}
 *       so non-contiguous selector indices work identically.</li>
 * </ul>
 *
 * <h3>The one semantic difference</h3>
 * DuckDB rejects rows with <em>more</em> columns than declared
 * ({@code TOO MANY COLUMNS}); the Java path keeps such rows and ignores the
 * extras. This is exactly what {@code skip_tail_columns} exists to handle, so
 * the {@code auto} engine policy ({@link #usesDuckDb}) routes any pipeline using
 * {@code skip_tail_columns}/{@code skip_junk_lines}/{@code skip_tail_lines} to the
 * Java path, leaving its behaviour untouched. Clean configs (all three zero) get
 * the native speedup automatically.
 */
public final class DuckDbCsvIngester {

    private static final Logger log = LoggerFactory.getLogger(DuckDbCsvIngester.class);

    private DuckDbCsvIngester() {}

    /**
     * Config-level native eligibility (no per-file knowledge).
     *
     * <ul>
     *   <li>{@code engine: duckdb} — always native (operator accepts any too-many-columns
     *       differences; the boundary pre-scan still resolves {@code skip}/width per file).</li>
     *   <li>{@code engine: java} — never native.</li>
     *   <li>{@code engine: auto} (default) — native unless {@code skip_tail_lines > 0}. As of 4.1
     *       the adaptive {@code skip_junk_lines} preamble and {@code skip_tail_columns} extra-column
     *       trimming are resolved natively by {@link BoundaryScanner} (see {@link #decideNative});
     *       only footer-line dropping ({@code skip_tail_lines}) still requires the Java parser.</li>
     * </ul>
     *
     * <p>This is the cheap gate; {@link #decideNative(Batch, PipelineConfig)} additionally confirms,
     * per file, that the boundaries actually resolve before committing a batch to the native path.
     */
    public static boolean usesDuckDb(PipelineConfig cfg) {
        // Fixed-width TEXT is parsed natively (read_csv + substring) only — the Java parser has no
        // fixed-width path, so it is always native regardless of the engine knob.
        if (cfg.fixedWidth() != null && !cfg.fixedWidth().binary()) return true;
        // json / text_regex frontends are native-only too (read_json / read_csv+regexp_extract).
        if (cfg.json() != null || cfg.textRegex() != null) return true;
        return switch (cfg.csv().engine() == null ? "auto" : cfg.csv().engine().toLowerCase()) {
            case "duckdb" -> true;
            case "java"   -> false;
            default       -> cfg.csv().skipTailLines() == 0;
        };
    }

    /**
     * Authoritative native-vs-Java decision for a whole batch. Returns {@code true} only when
     * {@link #usesDuckDb} is eligible <em>and</em> — for an {@code auto} config that uses the
     * adaptive knobs ({@code skip_junk_lines}/{@code skip_tail_columns}) — every member's boundaries
     * resolve via {@link BoundaryScanner}. If any member cannot be resolved, the whole batch falls
     * back to the Java parser, so behaviour never regresses relative to the pre-4.1 routing.
     *
     * <p>{@code engine: duckdb} is honoured verbatim (native regardless of scan); {@code engine: java}
     * is never native.
     */
    public static boolean decideNative(Batch batch, PipelineConfig cfg) {
        if (cfg.fixedWidth() != null && !cfg.fixedWidth().binary()) return true;   // fixed-width text: native-only
        if (cfg.json() != null || cfg.textRegex() != null) return true;           // json / text_regex: native-only
        String engine = cfg.csv().engine() == null ? "auto" : cfg.csv().engine().toLowerCase();
        if (engine.equals("java"))   return false;
        if (engine.equals("duckdb")) return true;
        // auto
        if (cfg.csv().skipTailLines() != 0) return false;                 // footer-drop stays Java
        boolean needsScan = cfg.csv().skipJunkLines() != 0 || cfg.csv().skipTailCols() != 0;
        if (!needsScan) return true;                                       // clean config → native
        for (Batch.Member m : batch.members()) {
            if (!BoundaryScanner.scan(m.file(), m.selection().schema(), cfg).resolved())
                return false;                                             // unresolved member → Java
        }
        return true;
    }

    /**
     * Ingest {@code file} into {@code targetTable} using DuckDB's native reader.
     * Drop-in replacement for {@link CsvIngester#ingest(File, Connection, Map, PipelineConfig, String)}.
     *
     * @throws IOException if the file cannot be read by DuckDB (→ {@code QUARANTINED_UNREADABLE})
     */
    public static IngestResult ingest(File file, Connection conn,
                                      Map<String, Object> schemaConfig,
                                      PipelineConfig cfg,
                                      String targetTable) throws Exception {

        ReadSpec spec = buildReadSpec(file, schemaConfig, cfg);

        long parsed;
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + targetTable + "\"");
            st.execute("CREATE TABLE \"" + targetTable + "\" AS SELECT " + spec.projection()
                    + " FROM " + spec.readCsv() + filterWhere(cfg));
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + targetTable + "\"")) {
                rs.next();
                parsed = rs.getLong(1);
            }
        } catch (Exception e) {
            // read_csv throws on an unreadable / nonexistent / undecodable file.
            throw new IOException("DuckDB read_csv failed for " + file.getName() + ": " + e.getMessage(), e);
        }

        long errors = drainRejects(conn, file, cfg);

        if (parsed > 0)
            log.info("[INGEST] [{}] {} rows (native read_csv){}",
                    file.getName(), String.format("%,d", parsed),
                    errors > 0 ? "  rejected=" + errors : "");

        // junkCandidateRows is a Java-parser concept; the native path folds all
        // dropped lines into errorRows, so report 0 here.
        return new IngestResult(parsed, errors, 0);
    }

    /**
     * Create a lazy {@code VIEW} over {@code read_csv} — projecting the schema columns plus a
     * constant {@code __src_id} — <em>without materialising any data</em>. Used by the
     * single-member streaming path ({@code CsvBatchStrategy}) so the whole pipeline
     * (read_csv → transform → partitioned COPY) runs in one streaming pass instead of copying
     * the data into {@code raw_f0} then {@code raw_input} before transforming.
     *
     * <p>The view embeds the <em>same</em> {@code read_csv} parameters as {@link #ingest} (including
     * {@code store_rejects=true}), so {@link #drainRejects} returns the same rejects once the view
     * has been consumed (e.g. by {@code CREATE TABLE transformed AS SELECT … FROM <viewName>}).
     *
     * @param viewName the view to (re)create — typically {@code "raw_input"}
     * @param srcId    the lineage tag baked into the view (single-member ⇒ {@code 0})
     */
    public static void createRawInputView(File file, Connection conn,
                                          Map<String, Object> schemaConfig,
                                          PipelineConfig cfg,
                                          String viewName, int srcId) throws Exception {
        ReadSpec spec = buildReadSpec(file, schemaConfig, cfg);
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS \"" + viewName + "\"");
            st.execute("CREATE VIEW \"" + viewName + "\" AS SELECT " + spec.projection()
                    + ", CAST(" + srcId + " AS INTEGER) AS __src_id FROM " + spec.readCsv()
                    + filterWhere(cfg));
        }
    }

    /** The {@code read_csv(...)} relation SQL and the selector projection for a schema. */
    private record ReadSpec(String projection, String readCsv) {}

    /** Build the {@code read_csv} relation + selector projection shared by {@link #ingest} and
     *  {@link #createRawInputView}. Pure string assembly — no DB contact. */
    private static ReadSpec buildReadSpec(File file, Map<String, Object> schemaConfig, PipelineConfig cfg) {
        if (cfg.fixedWidth() != null && !cfg.fixedWidth().binary())
            return buildFixedWidthReadSpec(file, schemaConfig, cfg);
        if (cfg.json() != null)
            return buildJsonReadSpec(file, schemaConfig, cfg);
        if (cfg.textRegex() != null)
            return buildTextRegexReadSpec(file, schemaConfig, cfg);

        ParserSpec spec = ParserSpec.fromSchema(schemaConfig);
        List<Map<String, Object>> fields = spec.fields();
        int[] selectorIdx = spec.selectorIdx();
        int physicalCols  = spec.physicalCols();

        String delim     = (cfg.csv().delimiter() != null && !cfg.csv().delimiter().isEmpty()) ? cfg.csv().delimiter() : ",";
        int    skipLines = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        String filePath  = file.getAbsolutePath().replace("\\", "/");

        // Resolve the adaptive knobs (preamble skip + physical width) via a cheap head-window scan,
        // so SQL*Plus-style preambles and extra trailing columns can be parsed natively. The scan is
        // only needed when those knobs are active; a clean config keeps the static skip + width.
        if (cfg.csv().skipJunkLines() != 0 || cfg.csv().skipTailCols() != 0) {
            BoundaryScanner.BoundaryScan bs = BoundaryScanner.scan(file, schemaConfig, cfg);
            if (bs.resolved()) {
                skipLines = bs.skip();
                if (cfg.csv().skipTailCols() > 0)
                    physicalCols = Math.max(physicalCols, bs.physicalWidth());
            }
        }

        // columns={'c0':'VARCHAR', 'c1':'VARCHAR', ...}
        StringBuilder cols = new StringBuilder("{");
        for (int i = 0; i < physicalCols; i++) {
            if (i > 0) cols.append(", ");
            cols.append("'c").append(i).append("':'VARCHAR'");
        }
        cols.append('}');

        // SELECT "c<sel>" AS "name", ...
        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) proj.append(", ");
            proj.append("\"c").append(selectorIdx[i]).append("\" AS \"")
                .append(fields.get(i).get("name")).append('"');
        }

        String readCsv = "read_csv('" + filePath + "'"
                + ", columns=" + cols
                + ", delim='" + escapeSql(delim) + "'"
                + ", header=false"
                + ", skip=" + skipLines
                + readOptions(cfg)
                + ", ignore_errors=true"
                + ", null_padding=false"
                + ", auto_detect=false"
                + ", store_rejects=true)";

        return new ReadSpec(proj.toString(), readCsv);
    }

    /**
     * Build the fixed-width read spec: read each physical line as a single VARCHAR column {@code line}
     * (an empty {@code delim}/{@code quote}/{@code escape} disables column-splitting and quote handling,
     * so {@code read_csv} keeps each physical line intact regardless of its bytes), then carve each
     * schema field with {@code substring}. Slice index =
     * {@code raw.fields[].selector}, so the projection produces the same named columns the delimited
     * path does and {@link DataTransformer} / {@link PartitionWriter} / {@link LineageCollector} run
     * unchanged. Lines shorter than {@code min_record_length} (blank lines, footers, banners) are
     * dropped by the inner {@code WHERE}. The result composes with the streaming/union/chunk callers
     * exactly like the delimited spec (their trailing {@link #filterWhere} is empty here).
     */
    private static ReadSpec buildFixedWidthReadSpec(File file, Map<String, Object> schemaConfig,
                                                    PipelineConfig cfg) {
        ParserSpec spec = ParserSpec.fromSchema(schemaConfig);
        List<Map<String, Object>> fields = spec.fields();
        int[] selectorIdx = spec.selectorIdx();
        PipelineConfig.FixedWidth fw = cfg.fixedWidth();
        List<PipelineConfig.FixedWidth.Slice> slices = fw.slices();

        int    skipLines = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        String filePath  = file.getAbsolutePath().replace("\\", "/");

        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            int sliceIdx = selectorIdx[i];
            if (sliceIdx >= slices.size())
                throw new IllegalArgumentException("fixedwidth: field '" + fields.get(i).get("name")
                        + "' selector " + sliceIdx + " has no matching slice (only " + slices.size() + " defined)");
            PipelineConfig.FixedWidth.Slice s = slices.get(sliceIdx);
            if (i > 0) proj.append(", ");
            // DuckDB substring is 1-based; config start is 0-based.
            proj.append(trimExpr(fw.trim(), "substring(\"line\", " + (s.start() + 1) + ", " + s.length() + ")"))
                .append(" AS \"").append(fields.get(i).get("name")).append('"');
        }

        String readCsv = "(SELECT \"line\" FROM read_csv('" + filePath + "'"
                + ", columns={'line':'VARCHAR'}"
                + ", delim='', quote='', escape=''"
                + ", header=false"
                + ", skip=" + skipLines
                + readOptions(cfg)
                + ", ignore_errors=true"
                + ", null_padding=true"
                + ", auto_detect=false"
                + ", store_rejects=true)"
                + " WHERE length(\"line\") >= " + fw.minRecordLength() + ") AS fw";

        return new ReadSpec(proj.toString(), readCsv);
    }

    /**
     * Build the JSON/NDJSON read spec. Each schema field lands as a VARCHAR column keyed by
     * {@code raw.fields[].selector} — for this frontend the selector is the top-level JSON key, not
     * a column index — so {@link DataTransformer} / {@code PartitionWriter} / lineage run unchanged.
     *
     * <p>{@code format: newline} (NDJSON, the default) reads each physical line intact via the
     * single-column {@code read_csv} form (streaming, gz-aware), keeps only {@code json_valid}
     * lines — a malformed line is routed away from the output instead of failing the batch (it has
     * no {@code store_rejects} entry, so it does not land in the errors CSV) — and carves each key
     * with {@code json_extract_string}. {@code format: array | auto} delegates to DuckDB
     * {@code read_json} with an explicit all-VARCHAR {@code columns} map (a malformed document
     * fails the file as unreadable, per JSON-array semantics).
     */
    private static ReadSpec buildJsonReadSpec(File file, Map<String, Object> schemaConfig,
                                              PipelineConfig cfg) {
        PipelineConfig.Json j = cfg.json();
        List<Map<String, Object>> fields = rawFields(schemaConfig);
        String filePath = file.getAbsolutePath().replace("\\", "/");

        if (j.newlineDelimited()) {
            int skipLines = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
            // json_extract_string("line", '$."key"') AS "name", ...
            StringBuilder proj = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) proj.append(", ");
                proj.append("json_extract_string(\"line\", '$.\"")
                    .append(escapeSql(String.valueOf(fields.get(i).get("selector"))))
                    .append("\"') AS \"").append(fields.get(i).get("name")).append('"');
            }
            String readCsv = "(SELECT \"line\" FROM read_csv('" + escapeSql(filePath) + "'"
                    + ", columns={'line':'VARCHAR'}"
                    + ", delim='', quote='', escape=''"
                    + ", header=false"
                    + ", skip=" + skipLines
                    + readOptions(cfg)
                    + ", ignore_errors=true"
                    + ", null_padding=true"
                    + ", auto_detect=false"
                    + ", store_rejects=true)"
                    + " WHERE json_valid(\"line\")) AS js";
            return new ReadSpec(proj.toString(), readCsv);
        }

        // format: array | auto → DuckDB read_json with an explicit all-VARCHAR columns map.
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> f : fields) keys.add(String.valueOf(f.get("selector")));
        StringBuilder cols = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            if (!first) cols.append(", ");
            first = false;
            cols.append('\'').append(escapeSql(k)).append("':'VARCHAR'");
        }
        cols.append('}');

        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) proj.append(", ");
            proj.append('"').append(fields.get(i).get("selector")).append("\" AS \"")
                .append(fields.get(i).get("name")).append('"');
        }

        StringBuilder rj = new StringBuilder("read_json('");
        rj.append(escapeSql(filePath)).append('\'')
          .append(", columns=").append(cols)
          .append(", format='").append("array".equals(j.format()) ? "array" : "auto").append('\'');
        if (cfg.csv().inputCompression() != null && !cfg.csv().inputCompression().isBlank())
            rj.append(", compression='").append(escapeSql(cfg.csv().inputCompression())).append('\'');
        rj.append(')');

        return new ReadSpec(proj.toString(), rj.toString());
    }

    /**
     * Build the text/regex read spec for the default (one-record-per-line) mode: read each
     * physical line intact as a single VARCHAR column (the fixed-width single-column
     * {@code read_csv} form — streaming, gz-aware, reject-capturing), keep the lines matching the
     * pattern, and extract every named capture group with {@code regexp_extract(..., name_list)}.
     * A schema field's {@code raw.fields[].selector} names the capture group feeding it, so the
     * projection produces the same named columns the delimited path does and the backend runs
     * unchanged. Non-matching lines (banners, continuations, blanks) are dropped by the
     * {@code regexp_matches} filter, exactly as fixed-width drops short lines. Delegates to
     * {@link #buildTextRegexBlockReadSpec} when {@code record_split} configures block records.
     */
    private static ReadSpec buildTextRegexReadSpec(File file, Map<String, Object> schemaConfig,
                                                   PipelineConfig cfg) {
        if (!"\n".equals(cfg.textRegex().recordSplit()))
            return buildTextRegexBlockReadSpec(file, schemaConfig, cfg);

        PipelineConfig.TextRegex tr = cfg.textRegex();
        List<Map<String, Object>> fields = rawFields(schemaConfig);

        int    skipLines = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        String filePath  = file.getAbsolutePath().replace("\\", "/");
        String pat       = escapeSql(tr.pattern());
        String names     = nameList(tr.groupNames());
        String proj      = textRegexProjection(fields, tr.groupNames());

        String readCsv = "(SELECT regexp_extract(\"line\", '" + pat + "', " + names + ") AS rec"
                + " FROM read_csv('" + escapeSql(filePath) + "'"
                + ", columns={'line':'VARCHAR'}"
                + ", delim='', quote='', escape=''"
                + ", header=false"
                + ", skip=" + skipLines
                + readOptions(cfg)
                + ", ignore_errors=true"
                + ", null_padding=true"
                + ", auto_detect=false"
                + ", store_rejects=true)"
                + " WHERE regexp_matches(\"line\", '" + pat + "')) AS tr";

        return new ReadSpec(proj, readCsv);
    }

    /**
     * Build the text/regex read spec for block mode ({@code record_split} other than one-record-
     * per-line): read the whole file as text, split it into records on the literal
     * {@code recordSplit} delimiter (e.g. {@code "\n\n"} for blank-line-separated blocks), drop
     * empty records and any leading records skipped by {@code skip_header_lines}, then match
     * {@code pattern} against each (trimmed) record's full text with {@code (?s)} so {@code .}
     * matches the newlines inside a multi-line record. Named capture groups are extracted exactly
     * as in the line-mode path, so the typing/mapping/partition/lineage backend runs unchanged.
     */
    private static ReadSpec buildTextRegexBlockReadSpec(File file, Map<String, Object> schemaConfig,
                                                        PipelineConfig cfg) {
        PipelineConfig.TextRegex tr = cfg.textRegex();
        List<Map<String, Object>> fields = rawFields(schemaConfig);

        int    skipRecords = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        String filePath     = file.getAbsolutePath().replace("\\", "/");
        String pat          = escapeSql("(?s)" + tr.pattern());
        String delim        = escapeSql(tr.recordSplit());
        String names        = nameList(tr.groupNames());
        String proj         = textRegexProjection(fields, tr.groupNames());

        String readText = "(SELECT regexp_extract(trim(blk), '" + pat + "', " + names + ") AS rec"
                + " FROM (SELECT unnest(list_slice(str_split(content, '" + delim + "'), "
                + (skipRecords + 1) + ", 2147483647)) AS blk"
                + " FROM read_text('" + escapeSql(filePath) + "'))"
                + " WHERE trim(blk) != ''"
                + " AND regexp_matches(trim(blk), '" + pat + "')) AS tr";

        return new ReadSpec(proj, readText);
    }

    /** name_list labels capture groups 1..n in declaration order → struct keys = group names. */
    private static String nameList(List<String> groupNames) {
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < groupNames.size(); i++) {
            if (i > 0) names.append(", ");
            names.append('\'').append(escapeSql(groupNames.get(i))).append('\'');
        }
        return names.append(']').toString();
    }

    /** {@code rec['<group>'] AS "<field>"} projection shared by the line- and block-mode read specs. */
    private static String textRegexProjection(List<Map<String, Object>> fields, List<String> groupNames) {
        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            String sel = String.valueOf(fields.get(i).get("selector"));
            if (!groupNames.contains(sel))
                throw new IllegalArgumentException("text_regex: field '" + fields.get(i).get("name")
                        + "' selector '" + sel + "' has no matching capture group (declared: "
                        + groupNames + ")");
            if (i > 0) proj.append(", ");
            proj.append("rec['").append(escapeSql(sel)).append("'] AS \"")
                .append(fields.get(i).get("name")).append('"');
        }
        return proj.toString();
    }

    /** The schema's {@code raw.fields} maps in declared order, selectors kept as raw strings. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawFields(Map<String, Object> schemaConfig) {
        return (List<Map<String, Object>>)
                ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
    }

    /** Wrap a substring expression in the configured trim function. */
    private static String trimExpr(PipelineConfig.FixedWidth.Trim trim, String inner) {
        return switch (trim) {
            case NONE  -> inner;
            case LEFT  -> "ltrim(" + inner + ")";
            case RIGHT -> "rtrim(" + inner + ")";
            case BOTH  -> "trim(" + inner + ")";
        };
    }

    /**
     * Drain this file's rejected rows from the connection's {@code reject_errors} table to
     * {@code errors/<base>_errors.csv} and return the reject count. Public so the streaming path
     * can call it after the {@code raw_input} view has been consumed. Safe to call even if the
     * file produced no rejects (returns {@code 0}).
     */
    public static long drainRejects(Connection conn, File file, PipelineConfig cfg) {
        return writeRejects(conn, file, file.getAbsolutePath().replace("\\", "/"), cfg);
    }

    // ── reject handling ─────────────────────────────────────────────────────

    /**
     * Drain this file's rows from the connection's {@code reject_errors} table to
     * {@code errors/<base>_errors.csv} and return the reject count. Scoped by
     * {@code file_path} so concurrent members on the same connection don't mix.
     * The reject tables only exist once {@code store_rejects} has fired at least
     * once on the connection, so failures here are swallowed (no rejects → no file).
     */
    private static long writeRejects(Connection conn, File file, String filePath,
                                     PipelineConfig cfg) {
        String sql =
                "SELECT e.line, e.column_name, e.error_type, e.csv_line " +
                "FROM reject_errors e JOIN reject_scans s USING (scan_id) " +
                "WHERE s.file_path = '" + escapeSql(filePath) + "' ORDER BY e.line";

        Path errorFilePath = ParserSpec.errorFile(file, cfg);
        Path errorDir      = errorFilePath.getParent();

        long count = 0;
        PrintWriter errOut = null;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                if (errOut == null) {
                    Files.createDirectories(errorDir);
                    errOut = new PrintWriter(Files.newBufferedWriter(errorFilePath));
                    errOut.println("line_number,column,reason,raw_line");
                }
                String raw = rs.getString("csv_line");
                errOut.printf("%d,%s,\"%s\",\"%s\"%n",
                        rs.getLong("line"),
                        nz(rs.getString("column_name")),
                        nz(rs.getString("error_type")),
                        raw == null ? "" : raw.replace("\"", "'"));
                count++;
            }
        } catch (Exception e) {
            // reject_errors absent (no rejects ever stored on this conn) or query
            // failed — treat as zero rejects rather than failing ingest.
            log.debug("No reject_errors for {} ({})", file.getName(), e.getMessage());
        } finally {
            if (errOut != null) errOut.close();
        }
        return count;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ── 4.1 native read_csv pass-throughs + row filters ─────────────────────────

    /**
     * Optional {@code read_csv} parameters threaded from the grammar/csv_settings: {@code encoding},
     * {@code compression}, {@code nullstr}, {@code strict_mode}. Each is emitted only when set, so a
     * config that doesn't use them produces the exact same SQL as before.
     */
    private static String readOptions(PipelineConfig cfg) {
        PipelineConfig.CsvSettings c = cfg.csv();
        StringBuilder sb = new StringBuilder();
        if (c.encoding() != null && !c.encoding().isBlank())
            sb.append(", encoding='").append(escapeSql(c.encoding())).append('\'');
        if (c.inputCompression() != null && !c.inputCompression().isBlank())
            sb.append(", compression='").append(escapeSql(c.inputCompression())).append('\'');
        if (c.nullStrings() != null && !c.nullStrings().isEmpty()) {
            sb.append(", nullstr=[");
            for (int i = 0; i < c.nullStrings().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('\'').append(escapeSql(c.nullStrings().get(i))).append('\'');
            }
            sb.append(']');
        }
        if (c.strictMode() != null)
            sb.append(", strict_mode=").append(c.strictMode().booleanValue());
        return sb.toString();
    }

    /**
     * Build the row-filter {@code WHERE} clause (empty when no filters are configured). Include
     * patterns (prefix {@code LIKE 'x%'} or {@code regexp_matches}) are OR'd (keep if any match);
     * exclude patterns are negated and AND'd (drop if any match); both are AND-combined so the
     * deny-list wins on overlap. The target is the physical column {@code c<filter_target_column>}.
     * See {@code docs/delimited-grammar-design.md} §6.2.1.
     */
    static String filterWhere(PipelineConfig cfg) {
        PipelineConfig.CsvSettings c = cfg.csv();
        if (!c.hasRowFilters()) return "";
        String col = "\"c" + c.filterTargetColumn() + "\"";

        List<String> includes = new ArrayList<>();
        for (String p : c.includePrefixes()) includes.add(col + " LIKE '" + escapeSql(p) + "%'");
        for (String r : c.includeRegex())    includes.add("regexp_matches(" + col + ", '" + escapeSql(r) + "')");

        List<String> excludes = new ArrayList<>();
        for (String p : c.excludePrefixes()) excludes.add(col + " NOT LIKE '" + escapeSql(p) + "%'");
        for (String r : c.excludeRegex())    excludes.add("NOT regexp_matches(" + col + ", '" + escapeSql(r) + "')");

        List<String> parts = new ArrayList<>();
        if (!includes.isEmpty()) parts.add("(" + String.join(" OR ",  includes) + ")");
        if (!excludes.isEmpty()) parts.add("(" + String.join(" AND ", excludes) + ")");
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    /** Escape single quotes for embedding inside a single-quoted SQL string literal. */
    private static String escapeSql(String s) { return s.replace("'", "''"); }
}
