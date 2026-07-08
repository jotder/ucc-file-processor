package com.gamma.alert;

import com.gamma.config.io.ConfigCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One executable alert rule, loaded from a {@code *_alert.toon} (v4.1, B5 — the execution half of
 * the agent's draft-only {@code diagnose-and-alert} skill; the operator saving the reviewed draft is
 * what arms it).
 *
 * <pre>
 *   alert {
 *     name:       high-error-rate          # kebab-case rule name
 *     metric:     error_rate               # error_rate | failed_batches | rejected_files | duration_ms
 *     comparator: gt                       # gt | gte | lt | lte
 *     threshold:  0.05                     # error_rate is a fraction in (0,1]
 *     window:     1h                       # Ns/Nm/Nh/Nd duration, or Nb = last N batches
 *     severity:   WARNING                  # INFO | WARNING | CRITICAL
 *     onPipeline: EVENTS                   # optional; null/absent = every pipeline
 *   }
 * </pre>
 *
 * <p><b>Measure rules (BI-5)</b> — replace {@code metric}+{@code window} with a Dataset measure,
 * evaluated over the current at-rest data via the headless BI evaluator on every sweep:
 *
 * <pre>
 *   alert {
 *     name:       low-total-revenue
 *     dataset:    sales_ds                 # a dataset component id
 *     measure:    sum(amount)              # count | agg(field), agg ∈ count/countDistinct/sum/avg/min/max
 *     comparator: lt
 *     threshold:  1000
 *     severity:   WARNING
 *   }
 * </pre>
 *
 * <h3>Metric semantics (over the batches ledger, within the window)</h3>
 * <ul>
 *   <li>{@code error_rate} — {@code 1 - sum(total_output_rows)/sum(total_input_rows)} (0 when no input)</li>
 *   <li>{@code failed_batches} — count of {@code status == FAILED}</li>
 *   <li>{@code rejected_files} — {@code sum(rejected_count)}</li>
 *   <li>{@code duration_ms} — average {@code duration_ms}</li>
 * </ul>
 */
public record AlertRule(String name, String metric, String comparator, double threshold,
                        String window, String severity, String onPipeline,
                        String dataset, String measure) {

    public static final Set<String> METRICS =
            Set.of("error_rate", "failed_batches", "rejected_files", "duration_ms");
    public static final Set<String> COMPARATORS = Set.of("gt", "gte", "lt", "lte");
    public static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");

    /** The historic ledger-metric rule shape (every pre-BI-5 caller). */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, null, null);
    }

    public AlertRule {
        require(name != null && !name.isBlank(), "alert.name is required");
        metric = lower(metric);
        comparator = lower(comparator);
        severity = severity == null ? null : severity.trim().toUpperCase(Locale.ROOT);
        window = window == null ? null : window.trim().toLowerCase(Locale.ROOT);
        dataset = (dataset == null || dataset.isBlank()) ? null : dataset.trim();
        measure = (measure == null || measure.isBlank()) ? null : measure.trim();
        if (dataset != null) {
            // Measure rule (BI-5): a scalar Measure over a Dataset; the ledger window does not apply.
            require(metric == null, "a measure alert (dataset:) must not also declare a ledger metric");
            require(window == null, "a measure alert (dataset:) takes no window (it reads current data)");
            require(com.gamma.query.DatasetMeasureProbe.validMeasure(measure),
                    "alert.measure must be count or agg(field) with agg ∈ count/countDistinct/sum/avg/min/max");
        } else {
            require(measure == null, "alert.measure requires alert.dataset");
            require(METRICS.contains(metric), "alert.metric must be one of " + METRICS);
            require(window != null && window.matches("\\d+[smhdb]"), "alert.window must be Ns/Nm/Nh/Nd or Nb");
        }
        require(COMPARATORS.contains(comparator), "alert.comparator must be one of " + COMPARATORS);
        require(SEVERITIES.contains(severity), "alert.severity must be one of " + SEVERITIES);
        require(threshold > 0, "alert.threshold must be a positive number");
        onPipeline = (onPipeline == null || onPipeline.isBlank()) ? null : onPipeline.trim();
    }

    /** Whether this is a BI-5 measure rule (a Dataset measure) vs a ledger-metric rule. */
    public boolean isMeasureRule() {
        return dataset != null;
    }

    /** Parse + validate from the decoded {@code alert { … }} map. */
    public static AlertRule fromMap(Map<String, Object> alert) {
        require(alert != null, "missing 'alert' block");
        return new AlertRule(
                str(alert.get("name")),
                str(alert.get("metric")),
                str(alert.get("comparator")),
                number(alert.get("threshold")),
                str(alert.get("window")),
                str(alert.get("severity")),
                str(alert.get("onPipeline")),
                str(alert.get("dataset")),
                str(alert.get("measure")));
    }

    /** Load a {@code *_alert.toon}. */
    @SuppressWarnings("unchecked")
    public static AlertRule load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object alert = root.get("alert");
        require(alert instanceof Map, path + " has no 'alert' block");
        return fromMap((Map<String, Object>) alert);
    }

    /** True when the window counts batches ({@code Nb}) rather than elapsed time. */
    public boolean batchWindow() {
        return window.endsWith("b");
    }

    /** The batch count for an {@code Nb} window. */
    public int windowBatches() {
        return Integer.parseInt(window.substring(0, window.length() - 1));
    }

    /** The elapsed-time span for a duration window. */
    public Duration windowDuration() {
        long n = Long.parseLong(window.substring(0, window.length() - 1));
        return switch (window.charAt(window.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalStateException("not a duration window: " + window);
        };
    }

    /** Apply the comparator to a computed metric value. */
    public boolean breached(double value) {
        return switch (comparator) {
            case "gt" -> value > threshold;
            case "gte" -> value >= threshold;
            case "lt" -> value < threshold;
            case "lte" -> value <= threshold;
            default -> false;
        };
    }

    /** JSON-ready view for {@code GET /alerts/rules}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        if (metric != null) m.put("metric", metric);
        if (dataset != null) m.put("dataset", dataset);
        if (measure != null) m.put("measure", measure);
        m.put("comparator", comparator);
        m.put("threshold", threshold);
        if (window != null) m.put("window", window);
        m.put("severity", severity);
        if (onPipeline != null) m.put("onPipeline", onPipeline);
        return m;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String lower(String v) {
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }

    private static double number(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String s = str(v);
        require(s != null, "alert.threshold is required");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("alert.threshold must be a number, got '" + s + "'");
        }
    }
}
