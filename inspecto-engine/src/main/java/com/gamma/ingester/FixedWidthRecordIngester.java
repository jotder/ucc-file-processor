package com.gamma.ingester;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import com.gamma.etl.StreamingFileIngester;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reference {@link StreamingFileIngester} for fixed-length <b>binary</b> records: each record is
 * exactly {@code record_length} bytes with no delimiter, carved into positional fields by byte
 * {@code (start,length)}. The fixed-width <em>text</em> case (newline-delimited records) is handled
 * natively by the engine ({@code frontend: fixedwidth} → {@code read_csv}+{@code substring}); reach
 * for this ingester only for binary / no-newline records the line reader cannot split.
 *
 * <h3>Wire-up</h3>
 * <pre>
 * processing:
 *   ingester: com.gamma.ingester.FixedWidthRecordIngester
 *   segments:
 *     REC: config/sub/sub_schema.toon          # exactly one segment
 *   ingester_config:
 *     record_length: 24
 *     encoding: utf-8                            # optional (default UTF-8)
 *     trim: both                                 # none|left|right|both (default both)
 *     fields[3]{name,start,length}:              # byte geometry, positional to the segment's raw.fields
 *       ACCOUNT_NUMBER,0,6
 *       EVENT_DATE,6,10
 *       AMOUNT,16,8
 * </pre>
 *
 * <p>Column <em>names/types</em> come from the segment schema's {@code raw.fields} (positional); this
 * config supplies only the byte geometry. A trailing partial record (fewer than {@code record_length}
 * bytes) is {@link RecordSink#reject rejected}; an {@code IOException} makes the framework quarantine
 * the file as {@code QUARANTINED_UNREADABLE}; zero emitted records ⇒ {@code QUARANTINED_MISMATCH}.
 */
public final class FixedWidthRecordIngester implements StreamingFileIngester {

    /** Public no-arg constructor required for reflective load by {@code BatchProcessor}. */
    public FixedWidthRecordIngester() {}

    @SuppressWarnings("unchecked")
    @Override
    public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
        Map<String, Object> ic = cfg.schemas().ingesterConfig();

        int recordLength = parseInt(ic.get("record_length"), 0);
        if (recordLength <= 0)
            throw new IllegalArgumentException(
                    "ingester_config.record_length must be > 0 for FixedWidthRecordIngester");
        Charset charset = Charset.forName(String.valueOf(ic.getOrDefault("encoding", "UTF-8")));
        Trim trim = Trim.parse(ic.get("trim"));

        if (!(ic.get("fields") instanceof List<?> fl) || fl.isEmpty())
            throw new IllegalArgumentException("ingester_config.fields[]{name,start,length} is required");
        int n = fl.size();
        int[] starts = new int[n], lengths = new int[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> f = (Map<String, Object>) fl.get(i);
            starts[i]  = parseInt(f.get("start"), -1);
            lengths[i] = parseInt(f.get("length"), 0);
            if (starts[i] < 0 || lengths[i] < 1)
                throw new IllegalArgumentException(
                        "ingester_config.fields[" + i + "] needs start >= 0 and length >= 1");
        }

        // Exactly one segment; column names come from its raw.fields (positional, like TypedRecordIngester).
        if (cfg.schemas().segments() == null || cfg.schemas().segments().size() != 1)
            throw new IllegalArgumentException("FixedWidthRecordIngester expects exactly one segment, got "
                    + (cfg.schemas().segments() == null ? "none" : cfg.schemas().segments().keySet()));
        String segKey = cfg.schemas().segments().keySet().iterator().next();
        List<Map<String, Object>> rawFields = (List<Map<String, Object>>)
                ((Map<String, Object>) cfg.schemas().segments().get(segKey).get("raw")).get("fields");
        List<String> cols = new ArrayList<>(rawFields.size());
        for (Map<String, Object> f : rawFields) cols.add((String) f.get("name"));
        if (cols.size() != n)
            throw new IllegalArgumentException("ingester_config.fields count (" + n + ") != segment '"
                    + segKey + "' raw.fields count (" + cols.size() + ")");
        sink.define(segKey, cols);

        byte[] buf = new byte[recordLength];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            int read;
            while ((read = in.readNBytes(buf, 0, recordLength)) != 0) {
                if (read < recordLength) { sink.reject(segKey); break; }   // partial trailing record
                Object[] vals = new Object[n];
                for (int i = 0; i < n; i++) {
                    int start = Math.min(starts[i], recordLength);
                    int len   = Math.min(lengths[i], recordLength - start);
                    vals[i] = trim.apply(new String(buf, start, len, charset));
                }
                sink.emit(segKey, vals);
            }
        }
    }

    private static int parseInt(Object v, int dflt) {
        if (v == null) return dflt;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? dflt : Integer.parseInt(s);
    }

    /** Field whitespace trimming (default {@link #BOTH}). */
    private enum Trim {
        NONE, LEFT, RIGHT, BOTH;
        static Trim parse(Object v) {
            if (v == null) return BOTH;
            return switch (String.valueOf(v).trim().toLowerCase()) {
                case "none", "false" -> NONE;
                case "left", "ltrim" -> LEFT;
                case "right", "rtrim" -> RIGHT;
                default -> BOTH;
            };
        }
        String apply(String s) {
            return switch (this) {
                case NONE  -> s;
                case LEFT  -> s.stripLeading();
                case RIGHT -> s.stripTrailing();
                case BOTH  -> s.strip();
            };
        }
    }
}
