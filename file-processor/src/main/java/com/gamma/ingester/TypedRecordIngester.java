package com.gamma.ingester;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import com.gamma.etl.StreamingFileIngester;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Reference {@link StreamingFileIngester} implementation for "typed record" text files —
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
 * {@link PipelineConfig.Schemas#segments()}.  Fields 1..N are mapped positionally
 * to that segment's {@code raw.fields} list (so the schema's field order must
 * match the input column order).  Lines whose type prefix isn't a known segment
 * are counted as junk candidates ({@link RecordSink#junk()}) and skipped.
 *
 * <h3>Storage strategy</h3>
 * Every emitted value is stored as {@code VARCHAR} in DuckDB regardless of the
 * schema's declared type.  {@code DataTransformer} applies the
 * CAST-to-VARCHAR + {@code TRY_STRPTIME} chain at transform time, so this is
 * the simplest correct choice — pre-typing would force every ingester to
 * re-implement the same date/timestamp parsing logic.
 *
 * <h3>Derived columns</h3>
 * In addition to the columns declared in {@code raw.fields}, the ingester
 * {@link RecordSink#define declares} a trailing {@code EVENT_TYPE} column on every
 * segment, emitted with the segment key (e.g. {@code "CALL"}, {@code "SMS"}).  This
 * lets schemas reference {@code EVENT_TYPE} as a partition source without having to
 * redeclare it in {@code raw.fields}.
 *
 * <h3>Configuration</h3>
 * The field delimiter is read from {@code csv_settings.delimiter} (default
 * {@code ","}).  Blank lines and lines starting with {@code #} are skipped
 * without being counted as errors — both are common in hand-edited fixture
 * files and operational test inputs.
 *
 * <h3>Counts</h3>
 * <ul>
 *   <li>{@link RecordSink#emit emit} — a successfully parsed line</li>
 *   <li>{@link RecordSink#reject reject} — known segment type but wrong field count</li>
 *   <li>{@link RecordSink#junk junk} — unknown segment type (skipped silently)</li>
 * </ul>
 *
 * <p>If <em>every</em> segment ends with 0 emitted rows the framework quarantines
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
public final class TypedRecordIngester implements StreamingFileIngester {

    /** Public no-arg constructor required by {@link Class#getDeclaredConstructor()}. */
    public TypedRecordIngester() {}

    @SuppressWarnings("unchecked")
    @Override
    public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
        // Declare each segment's columns once: declared raw.fields + the derived EVENT_TYPE.
        // The nested raw→fields cast is invariant per segment, so resolving it up front avoids
        // re-walking the config map on every input line.
        Map<String, Integer> declaredByKey = new LinkedHashMap<>();
        for (String key : cfg.schemas().segments().keySet()) {
            List<Map<String, Object>> fields = (List<Map<String, Object>>)
                    ((Map<String, Object>) cfg.schemas().segments().get(key).get("raw")).get("fields");
            List<String> cols = new ArrayList<>(fields.size() + 1);
            for (Map<String, Object> f : fields) cols.add((String) f.get("name"));
            cols.add("EVENT_TYPE");                       // derived partition column
            sink.define(key, cols);
            declaredByKey.put(key, fields.size());
        }

        String delimiter = cfg.csv().delimiter() != null && !cfg.csv().delimiter().isBlank()
                ? cfg.csv().delimiter() : ",";

        try (BufferedReader rd = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = rd.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;

                // Split with -1 so trailing empties are preserved (matters for
                // schemas where the last column is legitimately blank).
                String[] all = line.split(java.util.regex.Pattern.quote(delimiter), -1);
                String   key = all[0];

                Integer declared = declaredByKey.get(key);
                if (declared == null) {            // unknown segment type → junk candidate
                    sink.junk();
                    continue;
                }
                // all.length includes the type prefix, so data-column count is all.length - 1
                if (all.length - 1 != declared) {  // wrong field count → error row
                    sink.reject(key);
                    continue;
                }

                Object[] vals = new Object[declared + 1];
                System.arraycopy(all, 1, vals, 0, declared);
                vals[declared] = key;              // trailing EVENT_TYPE
                sink.emit(key, vals);
            }
        }
    }
}
