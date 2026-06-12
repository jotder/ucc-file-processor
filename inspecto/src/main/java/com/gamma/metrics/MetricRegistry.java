package com.gamma.metrics;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tiny, dependency-free metrics registry that exposes counters, gauges, and
 * histograms in the <a href="https://prometheus.io/docs/instrumenting/exposition_formats/">
 * Prometheus text exposition format</a>. Built for the M4 observability cut so the
 * fat-JAR gains a {@code /metrics} endpoint without pulling in Micrometer/Jetty.
 *
 * <p>A process-wide {@link #global()} instance is the idiomatic metrics pattern
 * (mirrors Micrometer's global registry): any layer records without constructor
 * threading, and the Control API scrapes the same instance. Thread-safe.
 *
 * <h3>Model</h3>
 * <ul>
 *   <li><b>counter</b> — monotonic; {@link #inc}.</li>
 *   <li><b>gauge</b> — arbitrary up/down; {@link #setGauge}. Register a
 *       {@link #addCollector collector} to refresh gauges lazily at scrape time
 *       (e.g. inbox lag, quarantine counts).</li>
 *   <li><b>histogram</b> — {@link #observe}; fixed second-scale buckets, emits
 *       {@code _bucket}/{@code _sum}/{@code _count}.</li>
 * </ul>
 * A series is identified by name + the sorted label set, so the same metric name
 * carries many label combinations (e.g. {@code pipeline}, {@code status}).
 */
public final class MetricRegistry {

    /** Second-scale histogram buckets (batch/enrichment durations). */
    private static final double[] BUCKETS =
            {0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300};

    private static final MetricRegistry GLOBAL = new MetricRegistry();

    /** The process-wide registry the service and Control API share. */
    public static MetricRegistry global() { return GLOBAL; }

    private final Map<String, String> help = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DoubleAdder>> counters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>>       gauges   = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Hist>>         histos   = new ConcurrentHashMap<>();
    private final List<Runnable> collectors = new CopyOnWriteArrayList<>();

    // ── recording ──────────────────────────────────────────────────────────────

    public void inc(String name, String help, Map<String, String> labels, double delta) {
        this.help.putIfAbsent(name, help);
        counters.computeIfAbsent(name, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(labelKey(labels), k -> new DoubleAdder()).add(delta);
    }

    public void inc(String name, String help, Map<String, String> labels) {
        inc(name, help, labels, 1);
    }

    public void setGauge(String name, String help, Map<String, String> labels, double value) {
        this.help.putIfAbsent(name, help);
        gauges.computeIfAbsent(name, k -> new ConcurrentHashMap<>()).put(labelKey(labels), value);
    }

    public void observe(String name, String help, Map<String, String> labels, double value) {
        this.help.putIfAbsent(name, help);
        histos.computeIfAbsent(name, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(labelKey(labels), k -> new Hist()).observe(value);
    }

    /** Register a callback run immediately before each {@link #scrape()} to refresh gauges. */
    public void addCollector(Runnable collector) { collectors.add(collector); }

    /** For tests: drop all series and collectors. */
    public void reset() {
        counters.clear(); gauges.clear(); histos.clear(); help.clear(); collectors.clear();
    }

    // ── exposition ───────────────────────────────────────────────────────────────

    /** Render all series in Prometheus text format. Runs scrape-time collectors first. */
    public String scrape() {
        for (Runnable c : collectors) {
            try { c.run(); } catch (Exception ignore) { /* a bad collector must not break scrape */ }
        }
        StringBuilder sb = new StringBuilder(2048);
        counters.forEach((name, series) -> {
            header(sb, name, "counter");
            series.forEach((labels, v) -> line(sb, name, labels, v.sum()));
        });
        gauges.forEach((name, series) -> {
            header(sb, name, "gauge");
            series.forEach((labels, v) -> line(sb, name, labels, v));
        });
        histos.forEach((name, series) -> {
            header(sb, name, "histogram");
            series.forEach((labels, h) -> h.render(sb, name, labels));
        });
        return sb.toString();
    }

    private void header(StringBuilder sb, String name, String type) {
        String h = help.getOrDefault(name, name);
        sb.append("# HELP ").append(name).append(' ').append(h).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void line(StringBuilder sb, String name, String labels, double v) {
        sb.append(name);
        if (!labels.isEmpty()) sb.append('{').append(labels).append('}');
        sb.append(' ').append(fmt(v)).append('\n');
    }

    /** Sorted {@code k="v"} label string (Prometheus-escaped); empty when no labels. */
    private static String labelKey(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) return "";
        TreeMap<String, String> sorted = new TreeMap<>(labels);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, val) -> {
            if (sb.length() > 0) sb.append(',');
            sb.append(k).append("=\"").append(escape(val)).append('"');
        });
        return sb.toString();
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15)
            return Long.toString((long) v);
        return Double.toString(v);
    }

    /** One histogram series: cumulative bucket counts + running sum/count. */
    private static final class Hist {
        private final LongAdder[] bucket = new LongAdder[BUCKETS.length];
        private final LongAdder count = new LongAdder();
        private final DoubleAdder sum = new DoubleAdder();
        Hist() { for (int i = 0; i < bucket.length; i++) bucket[i] = new LongAdder(); }

        void observe(double v) {
            sum.add(v); count.increment();
            for (int i = 0; i < BUCKETS.length; i++) if (v <= BUCKETS[i]) bucket[i].increment();
        }

        void render(StringBuilder sb, String name, String labels) {
            // Each observation increments every bucket whose le >= v, so bucket[i] is
            // already the cumulative "<= BUCKETS[i]" count Prometheus expects.
            String inner = labels.isEmpty() ? "" : labels + ",";
            for (int i = 0; i < BUCKETS.length; i++) {
                sb.append(name).append("_bucket{").append(inner)
                  .append("le=\"").append(fmt(BUCKETS[i])).append("\"} ")
                  .append(bucket[i].sum()).append('\n');
            }
            long total = count.sum();
            sb.append(name).append("_bucket{").append(inner).append("le=\"+Inf\"} ").append(total).append('\n');
            sb.append(name).append("_sum"); appendLabels(sb, labels); sb.append(' ').append(fmt(sum.sum())).append('\n');
            sb.append(name).append("_count"); appendLabels(sb, labels); sb.append(' ').append(total).append('\n');
        }

        private static void appendLabels(StringBuilder sb, String labels) {
            if (!labels.isEmpty()) sb.append('{').append(labels).append('}');
        }
    }
}
