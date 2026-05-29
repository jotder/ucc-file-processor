package com.gamma.ingester;

import com.gamma.etl.FileIngester;
import com.gamma.etl.IngestResult;
import com.gamma.etl.PipelineConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/**
 * Reference {@link FileIngester} implementation for "typed record" text files —
 * one record per line where the first field selects which segment the line
 * belongs to and the remaining fields are that segment's data columns.
 *
 * <h3>Input format</h3>
 * <pre>
 * CALL,C001,2020-04-03,42
 * SMS,S001,2020-04-03,+15551234567
 * CALL,C002,2020-04-04,17
 * </pre>
 *
 * <p>For each line, field 0 is matched against the keys of
 * {@link PipelineConfig#segmentSchemas}.  Fields 1..N are mapped positionally
 * to that segment's {@code raw.fields} list (so the schema's field order must
 * match the input column order).  Lines whose type prefix isn't in
 * {@code segmentSchemas} are counted as junk candidates and skipped.
 *
 * <h3>Storage strategy</h3>
 * All columns are stored as {@code VARCHAR} in DuckDB regardless of the
 * schema's declared type.  {@code DataTransformer} applies the
 * CAST-to-VARCHAR + {@code TRY_STRPTIME} chain at transform time, so this is
 * the simplest correct choice — pre-typing would force every ingester to
 * re-implement the same date/timestamp parsing logic.
 *
 * <h3>Derived columns</h3>
 * In addition to the columns declared in {@code raw.fields}, the ingester
 * automatically adds an {@code EVENT_TYPE VARCHAR} column to every raw table,
 * populated with the segment key (e.g. {@code "CALL"}, {@code "SMS"}).  This
 * lets schemas reference {@code EVENT_TYPE} as a partition source without
 * having to redeclare it in {@code raw.fields} — and matches the {@link
 * FileIngester} contract that derived columns sit alongside the declared
 * fields.
 *
 * <h3>Configuration</h3>
 * The field delimiter is read from {@link PipelineConfig#delimiter} (default
 * {@code ","}).  Blank lines and lines starting with {@code #} are skipped
 * without being counted as errors — both are common in hand-edited fixture
 * files and operational test inputs.
 *
 * <h3>Error reporting</h3>
 * Counts surface in {@link IngestResult}:
 * <ul>
 *   <li>{@code parsedRows}        — successfully inserted lines</li>
 *   <li>{@code errorRows}         — known segment type but wrong field count</li>
 *   <li>{@code junkCandidateRows} — unknown segment type (skipped silently)</li>
 * </ul>
 *
 * <p>If <em>every</em> segment ends with 0 parsed rows the framework quarantines
 * the file as {@code QUARANTINED_MISMATCH}.  An {@link IOException} during file
 * read causes {@code QUARANTINED_UNREADABLE} — the framework handles both;
 * implementations only need to throw or count.
 *
 * <h3>Wire-up</h3>
 * <pre>
 * processing:
 *   ingester: com.gamma.ingester.TypedRecordIngester
 *   segments:
 *     CALL: config/events/call_schema.toon
 *     SMS:  config/events/sms_schema.toon
 *   csv_settings:
 *     delimiter: ","
 * </pre>
 */
public final class TypedRecordIngester implements FileIngester {

    /** Public no-arg constructor required by {@link Class#getDeclaredConstructor()}. */
    public TypedRecordIngester() {}

    @SuppressWarnings("unchecked")
    @Override
    public List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg)
            throws IOException {

        // Per-segment row accumulators keyed by segment name.  We accumulate into
        // memory first, then bulk-insert, so a malformed line never partially
        // commits to one segment while another segment is still being parsed.
        Map<String, List<String[]>> rowsByKey = new LinkedHashMap<>();
        Map<String, Long>           errorsByKey = new LinkedHashMap<>();
        for (String key : cfg.schemas().segments().keySet()) {
            rowsByKey.put(key, new ArrayList<>());
            errorsByKey.put(key, 0L);
        }

        String  delimiter   = cfg.csv().delimiter() != null && !cfg.csv().delimiter().isBlank() ? cfg.csv().delimiter() : ",";
        long    junkRows    = 0;

        try (BufferedReader rd = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = rd.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;

                // Split with -1 so trailing empties are preserved (matters for
                // schemas where the last column is legitimately blank).
                String[] all = line.split(java.util.regex.Pattern.quote(delimiter), -1);
                String   key = all[0];

                if (!rowsByKey.containsKey(key)) {
                    junkRows++;
                    continue;
                }

                int declared = ((List<Map<String, Object>>)
                        ((Map<String, Object>) cfg.schemas().segments().get(key).get("raw")).get("fields")).size();

                // all.length includes the type prefix, so data-column count is all.length - 1
                if (all.length - 1 != declared) {
                    errorsByKey.merge(key, 1L, Long::sum);
                    continue;
                }

                String[] dataCols = new String[declared];
                System.arraycopy(all, 1, dataCols, 0, declared);
                rowsByKey.get(key).add(dataCols);
            }
        }

        // ── create + populate one table per segment ───────────────────────────
        List<Segment> out = new ArrayList<>(rowsByKey.size());
        for (Map.Entry<String, List<String[]>> entry : rowsByKey.entrySet()) {
            String              key       = entry.getKey();
            List<String[]>      rows      = entry.getValue();
            long                errors    = errorsByKey.get(key);
            Map<String, Object> segSchema = cfg.schemas().segments().get(key);

            List<Map<String, Object>> fields = (List<Map<String, Object>>)
                    ((Map<String, Object>) segSchema.get("raw")).get("fields");
            String table = "raw_" + key + "_f" + srcId;

            createTable(conn, table, fields);
            if (!rows.isEmpty()) bulkInsert(conn, table, fields, rows, key);

            // Per-segment junkRows are carried only on the first segment so the
            // framework's "0 parsedRows across all segments" check sees them.
            long segJunk = key.equals(rowsByKey.keySet().iterator().next()) ? junkRows : 0L;
            out.add(new Segment(key, table, new IngestResult(rows.size(), errors, segJunk)));
        }
        return out;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Create {@code table} with one VARCHAR column per declared field, in declared
     * order, plus a trailing {@code EVENT_TYPE VARCHAR} column for partition use.
     */
    private static void createTable(Connection conn, String table,
                                    List<Map<String, Object>> fields) throws IOException {
        StringBuilder ddl = new StringBuilder("CREATE TABLE \"").append(table).append("\" (");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) ddl.append(", ");
            String name = (String) fields.get(i).get("name");
            ddl.append('"').append(name).append("\" VARCHAR");
        }
        ddl.append(", \"EVENT_TYPE\" VARCHAR)");
        try (Statement st = conn.createStatement()) {
            st.execute(ddl.toString());
        } catch (Exception e) {
            throw new IOException("CREATE TABLE failed for " + table + ": " + e.getMessage(), e);
        }
    }

    /**
     * Insert all {@code rows} into {@code table} via a single prepared statement.
     * Appends {@code segmentKey} as the trailing {@code EVENT_TYPE} value.
     */
    private static void bulkInsert(Connection conn, String table,
                                   List<Map<String, Object>> fields,
                                   List<String[]> rows,
                                   String segmentKey) throws IOException {
        int dataCols = fields.size();
        StringBuilder sql = new StringBuilder("INSERT INTO \"").append(table).append("\" VALUES (");
        for (int i = 0; i < dataCols + 1; i++) {   // +1 for EVENT_TYPE
            if (i > 0) sql.append(", ");
            sql.append('?');
        }
        sql.append(')');
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (String[] r : rows) {
                for (int i = 0; i < r.length; i++) ps.setString(i + 1, r[i]);
                ps.setString(dataCols + 1, segmentKey);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new IOException("INSERT failed for " + table + ": " + e.getMessage(), e);
        }
    }
}
