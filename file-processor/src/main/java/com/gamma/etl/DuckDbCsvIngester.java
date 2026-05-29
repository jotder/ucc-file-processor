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
     * Decide whether {@code cfg} should use the native DuckDB reader.
     *
     * <ul>
     *   <li>{@code engine: duckdb} — always native (operator accepts any
     *       too-many-columns differences).</li>
     *   <li>{@code engine: java} — never native.</li>
     *   <li>{@code engine: auto} (default) — native only when the messy-file knobs
     *       ({@code skip_junk_lines}, {@code skip_tail_lines}, {@code skip_tail_columns})
     *       are all zero, so no existing source's parse semantics change.</li>
     * </ul>
     */
    public static boolean usesDuckDb(PipelineConfig cfg) {
        return switch (cfg.csvEngine == null ? "auto" : cfg.csvEngine.toLowerCase()) {
            case "duckdb" -> true;
            case "java"   -> false;
            default       -> cfg.skipJunkLines == 0
                          && cfg.skipTailLines == 0
                          && cfg.skipTailCols  == 0;
        };
    }

    /**
     * Ingest {@code file} into {@code targetTable} using DuckDB's native reader.
     * Drop-in replacement for {@link CsvIngester#ingest(File, Connection, Map, PipelineConfig, String)}.
     *
     * @throws IOException if the file cannot be read by DuckDB (→ {@code QUARANTINED_UNREADABLE})
     */
    @SuppressWarnings("unchecked")
    public static IngestResult ingest(File file, Connection conn,
                                      Map<String, Object> schemaConfig,
                                      PipelineConfig cfg,
                                      String targetTable) throws Exception {

        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");

        // Selector indices + width of the physical column set we declare to DuckDB.
        int[] selectorIdx = new int[fields.size()];
        int maxSelector = 0;
        for (int i = 0; i < fields.size(); i++) {
            int sel = Integer.parseInt(String.valueOf(fields.get(i).get("selector")));
            selectorIdx[i] = sel;
            if (sel > maxSelector) maxSelector = sel;
        }
        int physicalCols = maxSelector + 1;

        String delim     = (cfg.delimiter != null && !cfg.delimiter.isEmpty()) ? cfg.delimiter : ",";
        int    skipLines = cfg.skipHeaderLines + (cfg.hasHeader ? 1 : 0);
        String filePath  = file.getAbsolutePath().replace("\\", "/");

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
                + ", ignore_errors=true"
                + ", null_padding=false"
                + ", auto_detect=false"
                + ", store_rejects=true)";

        long parsed;
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + targetTable + "\"");
            st.execute("CREATE TABLE \"" + targetTable + "\" AS SELECT " + proj + " FROM " + readCsv);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + targetTable + "\"")) {
                rs.next();
                parsed = rs.getLong(1);
            }
        } catch (Exception e) {
            // read_csv throws on an unreadable / nonexistent / undecodable file.
            throw new IOException("DuckDB read_csv failed for " + file.getName() + ": " + e.getMessage(), e);
        }

        long errors = writeRejects(conn, file, filePath, cfg);

        if (parsed > 0)
            log.info("[INGEST] [{}] {} rows (native read_csv){}",
                    file.getName(), String.format("%,d", parsed),
                    errors > 0 ? "  rejected=" + errors : "");

        // junkCandidateRows is a Java-parser concept; the native path folds all
        // dropped lines into errorRows, so report 0 here.
        return new IngestResult(parsed, errors, 0);
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

        Path errorDir      = Paths.get(cfg.errorsDir).toAbsolutePath();
        String baseName    = CsvIngester.stripExtensions(file.getName());
        Path errorFilePath = errorDir.resolve(baseName + "_errors.csv");

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

    /** Escape single quotes for embedding inside a single-quoted SQL string literal. */
    private static String escapeSql(String s) { return s.replace("'", "''"); }
}
