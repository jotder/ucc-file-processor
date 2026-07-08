package com.gamma.alert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One fired alert (v4.1, B5): a rule whose metric breached its threshold for a pipeline's recent
 * batches. Summaries only — no data-plane values. Backs {@code GET /alerts}, newest first.
 */
public record Alert(String rule, String severity, String pipeline, String metric, double value,
                    String comparator, double threshold, String window, long epochMillis,
                    String message) {

    static Alert of(AlertRule r, String pipeline, double value, long epochMillis) {
        // A measure rule (BI-5) has no ledger metric/window: label it by its measure over its dataset.
        String metricLabel = r.metric() != null ? r.metric() : r.measure();
        String windowLabel = r.window() != null ? r.window() : "current data";
        String msg = String.format(java.util.Locale.ROOT,
                "%s: %s %s is %s (threshold %s %s over %s)",
                r.severity(), pipeline, metricLabel, trim(value), r.comparator(), trim(r.threshold()),
                windowLabel);
        return new Alert(r.name(), r.severity(), pipeline, metricLabel, value, r.comparator(),
                r.threshold(), r.window(), epochMillis, msg);
    }

    /** JSON-ready view. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("severity", severity);
        m.put("pipeline", pipeline);
        m.put("metric", metric);
        m.put("value", value);
        m.put("comparator", comparator);
        m.put("threshold", threshold);
        m.put("window", window);
        m.put("epochMillis", epochMillis);
        m.put("message", message);
        return m;
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
