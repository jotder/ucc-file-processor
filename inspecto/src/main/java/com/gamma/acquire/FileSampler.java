package com.gamma.acquire;

import com.gamma.acquire.ConnectionWorkbench.SampleResult;
import com.gamma.util.DuckDbUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded, preview-only sampling of a landed file for the connection workbench (see
 * {@link ConnectionWorkbench#sample}). Parses tabular formats with the production DuckDB readers
 * ({@code read_csv} auto-detect / {@code read_parquet} / {@code read_json_auto}) on a throwaway scratch
 * database — the {@link com.gamma.pipeline.exec.ComponentPreview} idiom — and falls back to a raw
 * line-per-row preview (single {@code line} column) for anything else. Nothing is persisted.
 *
 * <p>Remote workbenches fetch a bounded head of the file to a temp location first and pass
 * {@code complete=false} when the fetch stopped early; a partially-fetched Parquet or gzip file cannot be
 * parsed at all and is refused with an honest detail rather than a misleading parse error.
 */
public final class FileSampler {

    private FileSampler() {}

    /**
     * Sample the first {@code limit} rows of {@code file}. {@code displayPath} is the connector-relative
     * locator echoed back in the result (never the local temp path a remote fetch used); {@code complete}
     * is false when only a head of the original file was materialised.
     */
    public static SampleResult sample(Path file, String displayPath, int limit, boolean complete)
            throws AcquisitionException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean gz = name.endsWith(".gz");
        String ext = ext(gz ? name.substring(0, name.length() - 3) : name);

        if (!complete && (gz || "parquet".equals(ext)))
            return new SampleResult(displayPath, List.of(), List.of(), true,
                    "file was only partially fetched — cannot parse a " + (gz ? "compressed" : ext) + " preview");

        String reader = switch (ext) {
            case "parquet" -> "read_parquet(" + sqlStr(file) + ")";
            case "csv", "tsv" -> "read_csv(" + sqlStr(file)
                    + ", auto_detect=true, all_varchar=true, ignore_errors=true)";
            case "json", "ndjson", "jsonl" -> "read_json_auto(" + sqlStr(file) + ")";
            default -> null;
        };
        if (reader == null) return rawLines(file, displayPath, limit, complete);

        File db = null;
        try {
            DuckDbUtil.loadDriver();
            db = DuckDbUtil.tempDbFile("workbench_sample_");
            try (Connection conn = DuckDbUtil.openConnection(db)) {
                try (Statement st = conn.createStatement()) {
                    // limit+1 so `truncated` is answered without scanning the whole file
                    st.execute("CREATE TABLE ws AS SELECT * FROM " + reader + " LIMIT " + (limit + 1));
                }
                List<String> columns = new ArrayList<>();
                List<Map<String, Object>> rows = new ArrayList<>();
                int seen = 0;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM ws")) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
                    while (rs.next()) {
                        if (++seen > limit) break;
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) row.put(columns.get(i - 1), jsonSafe(rs.getObject(i)));
                        rows.add(row);
                    }
                }
                boolean truncated = seen > limit || !complete;
                return new SampleResult(displayPath, columns, rows, truncated,
                        "showing " + rows.size() + (truncated ? "+" : "") + " rows");
            }
        } catch (ClassNotFoundException | SQLException | IOException e) {
            throw new AcquisitionException("Cannot parse a sample from " + displayPath + ": " + e.getMessage(), e);
        } finally {
            if (db != null) DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Fallback preview: each physical line intact as one row of a single {@code line} column. */
    private static SampleResult rawLines(Path file, String displayPath, int limit, boolean complete)
            throws AcquisitionException {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean more = false;
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (rows.size() >= limit) { more = true; break; }
                rows.add(Map.of("line", line));
            }
        } catch (IOException e) {
            throw new AcquisitionException("Cannot read " + displayPath + ": " + e.getMessage(), e);
        }
        return new SampleResult(displayPath, List.of("line"), rows, more || !complete,
                "raw line preview (" + rows.size() + " lines)");
    }

    /** Numbers/booleans pass through; anything else (dates, timestamps, blobs) becomes its string form. */
    private static Object jsonSafe(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        return String.valueOf(v);
    }

    private static String sqlStr(Path p) {
        return "'" + p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''") + "'";
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }
}
