package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.Map;

/**
 * <b>T13 — the {@code adapter} node's micro-batch window.</b> Rather than a streaming runtime, a non-file
 * / push source is consumed by an {@code adapter} entry node that <b>windows</b> incoming records and
 * <b>lands a file</b> (§3.6), from which the normal {@code acquisition → parser → …} flow runs unchanged.
 * This is the flush policy: the window closes on whichever of {@code max_records} / {@code max_bytes} /
 * {@code max_age} is reached first (a {@code <= 0} bound is "no limit on this axis").
 *
 * <p>The actual stream consumer (Kafka / webhook / WatchService) and the landing are node-level concerns
 * built independently; this models only when the current window should flush. {@code max_age} uses the
 * engine's {@code Ns}/{@code Nm}/{@code Nh}/{@code Nd} duration convention.
 */
@PublicApi(since = "4.3.0")
public record AdapterWindow(long maxRecords, long maxBytes, long maxAgeMs) {

    /** Whether the current window should flush given its accumulated counts + age. */
    public boolean shouldFlush(long records, long bytes, long ageMs) {
        return (maxRecords > 0 && records >= maxRecords)
                || (maxBytes > 0 && bytes >= maxBytes)
                || (maxAgeMs > 0 && ageMs >= maxAgeMs);
    }

    /** Whether no bound is set on any axis (the window would never flush on its own). */
    public boolean unbounded() {
        return maxRecords <= 0 && maxBytes <= 0 && maxAgeMs <= 0;
    }

    /** Parse the {@code adapter} node's window config ({@code max_records}/{@code max_bytes}/{@code max_age}). */
    public static AdapterWindow of(PipelineNode adapter) {
        Map<String, Object> c = adapter.config();
        return new AdapterWindow(
                asLong(c.get("max_records")),
                asLong(c.get("max_bytes")),
                PipelineTrigger.millis(c.get("max_age")));
    }

    private static long asLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        String s = v.toString().trim();
        return s.isEmpty() ? 0 : Long.parseLong(s);
    }
}
