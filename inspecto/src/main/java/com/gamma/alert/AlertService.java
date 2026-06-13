package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.service.StatusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The alert execution engine (v4.1, B5) — evaluates operator-saved {@code *_alert.toon} rules
 * against the batches ledger and records breaches. This is the runtime half of the agent's
 * draft-only {@code diagnose-and-alert} skill: the agent proposes, a human saves the reviewed
 * {@code .toon}, and THIS deterministic service (no model, lean core) executes it.
 *
 * <h3>Evaluation model</h3>
 * Event-driven: every terminal {@link BatchEvent} (SUCCESS and FAILED both fire — error rates need
 * both) triggers evaluation of the rules scoped to that pipeline (plus unscoped rules) over the
 * ledger window. A manual sweep ({@link #evaluateAll}) backs {@code POST /alerts/evaluate}.
 *
 * <h3>Re-fire suppression</h3>
 * A rule that stays breached would otherwise fire on every batch; a per-rule+pipeline cooldown of
 * the rule's window length (10 minutes for batch-count windows, minimum 1 minute) suppresses
 * duplicates while the condition persists.
 *
 * <p>Fired alerts live in a bounded in-memory ring (newest first, like the diagnosis store);
 * process lifetime, capacity {@value #DEFAULT_CAPACITY}.
 */
public final class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    public static final int DEFAULT_CAPACITY = 1024;
    private static final DateTimeFormatter LEDGER_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<AlertRule> rules;
    private final ConfigSource configs;
    private final StatusStore status;
    private final Deque<Alert> fired = new ArrayDeque<>();
    private final int capacity;
    private final Map<String, Long> lastFired = new ConcurrentHashMap<>();

    public AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status) {
        this(rules, configs, status, DEFAULT_CAPACITY);
    }

    AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status, int capacity) {
        this.rules = List.copyOf(rules);
        this.configs = configs;
        this.status = status;
        this.capacity = Math.max(1, capacity);
    }

    /** The loaded rules, JSON-ready — backs {@code GET /alerts/rules}. */
    public List<Map<String, Object>> rules() {
        return rules.stream().map(AlertRule::toMap).toList();
    }

    /** Recent fired alerts, newest first, JSON-ready — backs {@code GET /alerts}. */
    public synchronized List<Map<String, Object>> recent(int limit) {
        return fired.stream().limit(Math.max(0, limit)).map(Alert::toMap).toList();
    }

    /** Bus subscriber: a terminal batch re-evaluates the rules scoped to its pipeline. */
    public void onEvent(BatchEvent event) {
        try {
            evaluate(event.pipeline(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            // Alerting must never disturb ingest: log and move on.
            log.warn("alert evaluation failed after batch {}: {}", event.batchId(), e.getMessage());
        }
    }

    /** Evaluate every rule against every (matching) pipeline — backs {@code POST /alerts/evaluate}. */
    public List<Map<String, Object>> evaluateAll() {
        return evaluate(null, System.currentTimeMillis());
    }

    /**
     * Evaluate rules; {@code pipelineFilter} (a pipeline's display or normalized name) restricts to
     * one pipeline's ledger, {@code null} sweeps all. Returns the alerts fired by this pass.
     */
    synchronized List<Map<String, Object>> evaluate(String pipelineFilter, long nowMs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PipelineConfig cfg : configs.pipelines()) {
            String display = cfg.identity().name();
            String id = cfg.identity().pipelineName();
            if (pipelineFilter != null && !matches(pipelineFilter, display, id)) continue;

            List<Map<String, String>> ledger = null;
            for (AlertRule rule : rules) {
                if (rule.onPipeline() != null && !matches(rule.onPipeline(), display, id)) continue;
                if (ledger == null) ledger = status.batches(cfg);   // one read per pipeline pass
                List<Map<String, String>> rows = inWindow(rule, ledger, nowMs);
                if (rows.isEmpty()) continue;
                double value = metricValue(rule.metric(), rows);
                if (!rule.breached(value)) continue;
                String key = rule.name() + "|" + id;
                long cooldown = cooldownMs(rule);
                Long last = lastFired.get(key);
                if (last != null && nowMs - last < cooldown) continue;   // still in cooldown
                lastFired.put(key, nowMs);
                Alert alert = Alert.of(rule, display, value, nowMs);
                fired.addFirst(alert);
                while (fired.size() > capacity) fired.removeLast();
                out.add(alert.toMap());
                log.warn("[ALERT] {}", alert.message());
                // Phase-1↔2 tie: a fired alert is also a structured operational event, so the Event
                // Viewer shows it inline with the batch facts that triggered it (correlate via pipeline).
                EventLog.global().emit(Event.builder(EventType.ALERT_FIRED)
                        .level(EventLevel.WARN)
                        .source(AlertService.class.getName())
                        .pipeline(display)
                        .message(alert.message())
                        .attr("rule", rule.name())
                        .attr("metric", rule.metric())
                        .attr("value", value)
                        .attr("severity", rule.severity()));
            }
        }
        return out;
    }

    // ── metric math over ledger rows ─────────────────────────────────────────────────────

    private static double metricValue(String metric, List<Map<String, String>> rows) {
        return switch (metric) {
            case "failed_batches" -> rows.stream()
                    .filter(r -> "FAILED".equalsIgnoreCase(r.getOrDefault("status", ""))).count();
            case "rejected_files" -> rows.stream().mapToLong(r -> asLong(r.get("rejected_count"))).sum();
            case "duration_ms" -> rows.stream().mapToLong(r -> asLong(r.get("duration_ms")))
                    .average().orElse(0);
            case "error_rate" -> {
                long in = rows.stream().mapToLong(r -> asLong(r.get("total_input_rows"))).sum();
                long outRows = rows.stream().mapToLong(r -> asLong(r.get("total_output_rows"))).sum();
                yield in <= 0 ? 0.0 : 1.0 - ((double) Math.min(outRows, in) / in);
            }
            default -> 0.0;
        };
    }

    /** The ledger rows the rule's window selects: last-N batches, or rows newer than now - span. */
    private static List<Map<String, String>> inWindow(AlertRule rule, List<Map<String, String>> ledger,
                                                      long nowMs) {
        if (rule.batchWindow()) {
            int n = rule.windowBatches();
            return ledger.size() <= n ? ledger : ledger.subList(ledger.size() - n, ledger.size());
        }
        Duration span = rule.windowDuration();
        LocalDateTime cutoff = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs),
                java.time.ZoneId.systemDefault()).minus(span);
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> r : ledger) {
            LocalDateTime ts = parseTs(r.get("end_time"), r.get("start_time"));
            if (ts != null && !ts.isBefore(cutoff)) out.add(r);
        }
        return out;
    }

    /** Lenient ledger timestamp parse ({@code yyyy-MM-dd HH:mm:ss}, T-separator tolerated). */
    private static LocalDateTime parseTs(String... candidates) {
        for (String s : candidates) {
            if (s == null || s.length() < 19) continue;
            try {
                return LocalDateTime.parse(s.substring(0, 19).replace('T', ' '), LEDGER_TS);
            } catch (RuntimeException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    private static long cooldownMs(AlertRule rule) {
        long ms = rule.batchWindow() ? Duration.ofMinutes(10).toMillis()
                : rule.windowDuration().toMillis();
        return Math.max(ms, Duration.ofMinutes(1).toMillis());
    }

    private static boolean matches(String wanted, String display, String id) {
        String w = wanted.trim().toLowerCase(Locale.ROOT);
        return w.equals(display.toLowerCase(Locale.ROOT)) || w.equals(id.toLowerCase(Locale.ROOT))
                || w.replace(' ', '_').equals(id.toLowerCase(Locale.ROOT));
    }

    private static long asLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return (long) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
