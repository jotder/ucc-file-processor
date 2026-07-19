package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.service.StatusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
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

    /** Armed rules; swapped atomically by the authoring mutators ({@link #upsert}/{@link #remove}). */
    private volatile List<AlertRule> rules;
    private final ConfigSource configs;
    private final StatusStore status;
    /** Object store for persisting fired alerts as managed objects (Phase 2); {@code null} = events-only. */
    private final ObjectService objects;
    private final Deque<Alert> fired = new ArrayDeque<>();
    private final int capacity;
    private final Map<String, Long> lastFired = new ConcurrentHashMap<>();
    /** BI-5: evaluates {@code (dataset, measure)} → current scalar value; {@code null} disables measure rules. */
    private volatile java.util.function.BiFunction<String, String, java.util.OptionalDouble> measureProbe;

    public AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status) {
        this(rules, configs, status, (ObjectService) null);
    }

    /**
     * Phase 2: also persist each fired alert as an {@link ObjectType#ALERT}
     * {@link com.gamma.ops.OperationalObject} through {@code objects}. A {@code null} {@code objects}
     * keeps the prior events-only behaviour (the lean path and unit tests).
     */
    public AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status,
                        ObjectService objects) {
        this(rules, configs, status, objects, DEFAULT_CAPACITY);
    }

    AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status, int capacity) {
        this(rules, configs, status, null, capacity);
    }

    AlertService(List<AlertRule> rules, ConfigSource configs, StatusStore status,
                 ObjectService objects, int capacity) {
        this.rules = List.copyOf(rules);
        this.configs = configs;
        this.status = status;
        this.objects = objects;
        this.capacity = Math.max(1, capacity);
    }

    /** Wire the BI-5 measure evaluator (BiFunction so this engine stays decoupled from the query layer). */
    public void measureProbe(java.util.function.BiFunction<String, String, java.util.OptionalDouble> probe) {
        this.measureProbe = probe;
    }

    /** The loaded rules, JSON-ready — backs {@code GET /alerts/rules}. */
    public List<Map<String, Object>> rules() {
        return rules.stream().map(AlertRule::toMap).toList();
    }

    /** True when a rule with this name is armed (the create-conflict / update-exists check). */
    public boolean has(String name) {
        return rules.stream().anyMatch(r -> r.name().equals(name));
    }

    /**
     * Arm a rule at runtime (authoring: {@code POST}/{@code PUT /alerts/rules}), replacing any existing
     * rule of the same name. The name is the identity, so an upsert is add-or-replace. The next batch
     * event (or {@code POST /alerts/evaluate}) evaluates it. Serialised against {@link #evaluate}.
     */
    public synchronized void upsert(AlertRule rule) {
        List<AlertRule> next = new ArrayList<>(rules.size() + 1);
        for (AlertRule r : rules) if (!r.name().equals(rule.name())) next.add(r);
        next.add(rule);
        this.rules = List.copyOf(next);
    }

    /** Disarm a rule by name ({@code DELETE /alerts/rules/{name}}); {@code true} if one was armed. */
    public synchronized boolean remove(String name) {
        List<AlertRule> next = rules.stream().filter(r -> !r.name().equals(name)).toList();
        boolean removed = next.size() != rules.size();
        this.rules = List.copyOf(next);
        return removed;
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

        // Measure rules (BI-5) are dataset-scoped, not pipeline-scoped: any sweep (a terminal batch may
        // have changed the data, or the manual POST /alerts/evaluate) re-reads the current value; the
        // cooldown suppresses repeats. Skipped silently when no probe is wired (lean/unit paths).
        for (AlertRule rule : rules) {
            if (!rule.isMeasureRule()) continue;
            var probe = measureProbe;
            if (probe == null) continue;
            java.util.OptionalDouble value = probe.apply(rule.dataset(), rule.measure());
            if (value.isEmpty() || !rule.breached(value.getAsDouble())) continue;
            fire(rule, rule.dataset(), rule.dataset(), value.getAsDouble(), nowMs, out);
        }

        for (PipelineConfig cfg : configs.pipelines()) {
            String display = cfg.identity().name();
            String id = cfg.identity().pipelineName();
            if (pipelineFilter != null && !matches(pipelineFilter, display, id)) continue;

            List<Map<String, String>> ledger = null;
            for (AlertRule rule : rules) {
                if (rule.isMeasureRule()) continue;
                if (rule.onPipeline() != null && !matches(rule.onPipeline(), display, id)) continue;
                if (ledger == null) ledger = status.batches(cfg);   // one read per pipeline pass
                List<Map<String, String>> rows = inWindow(rule, ledger, nowMs);
                if (rule.when() != null) rows = filterByWhen(rule.when(), rows);
                if (rows.isEmpty()) continue;
                double value = metricValue(rule.metric(), rows);
                if (!rule.breached(value)) continue;
                fire(rule, display, id, value, nowMs, out);
            }
        }
        return out;
    }

    /** Fire one breached rule for a scope (a pipeline, or a measure rule's dataset), cooldown-guarded. */
    private void fire(AlertRule rule, String display, String cooldownScope, double value, long nowMs,
                      List<Map<String, Object>> out) {
        String key = rule.name() + "|" + cooldownScope;
        Long last = lastFired.get(key);
        if (last != null && nowMs - last < cooldownMs(rule)) return;   // still in cooldown
        lastFired.put(key, nowMs);
        Alert alert = Alert.of(rule, display, value, nowMs);
        fired.addFirst(alert);
        while (fired.size() > capacity) fired.removeLast();
        out.add(alert.toMap());
        log.warn("[ALERT] {}", alert.message());
        // Phase-1↔2 tie: a fired alert is also a structured operational event, so the Event
        // Viewer shows it inline with the batch facts that triggered it (correlate via pipeline).
        // Built explicitly so the persisted alert object (Phase 2) can link back to its id.
        Event firedEvent = Event.builder(EventType.ALERT_FIRED)
                .level(EventLevel.WARN)
                .source(AlertService.class.getName())
                .pipeline(display)
                .message(alert.message())
                .attr("rule", rule.name())
                .attr("metric", alert.metric())
                .attr("value", value)
                .attr("severity", rule.severity())
                .build();
        EventLog.current().emit(firedEvent);
        persistAlertObject(rule, alert, display, value, firedEvent.eventId());
    }

    /**
     * Phase 2: promote a fired alert to a managed {@link ObjectType#ALERT}
     * {@link com.gamma.ops.OperationalObject}, linked to the firing event via the {@code causedByEvent}
     * attribute. No-op when no object store is wired (events-only). A still-active (non-terminal) object
     * for the same rule+pipeline suppresses a duplicate — the cooldown throttles re-fires within a
     * window; this guards across windows so an operator handling one breach isn't handed a clone.
     * Never disturbs evaluation: a persistence failure is logged and swallowed.
     */
    private void persistAlertObject(AlertRule rule, Alert alert, String pipeline, double value,
                                    String eventId) {
        if (objects == null) return;
        try {
            boolean active = objects.active(ObjectType.ALERT, pipeline).stream()
                    .anyMatch(o -> rule.name().equals(o.attributes().get("rule")));
            if (active) return;
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("rule", rule.name());
            if (rule.metric() != null) attrs.put("metric", rule.metric());
            if (rule.dataset() != null) attrs.put("dataset", rule.dataset());
            if (rule.measure() != null) attrs.put("measure", rule.measure());
            attrs.put("comparator", rule.comparator());
            attrs.put("threshold", String.valueOf(rule.threshold()));
            if (rule.window() != null) attrs.put("window", rule.window());
            attrs.put("value", String.valueOf(value));
            if (eventId != null) attrs.put("causedByEvent", eventId);
            objects.open(ObjectType.ALERT, rule.name() + " on " + pipeline, alert.message(),
                    rule.severity(), pipeline, attrs);
            promoteToIncident(rule, alert, pipeline, attrs);
        } catch (RuntimeException e) {
            log.warn("could not persist alert object for rule {}: {}", rule.name(), e.getMessage());
        }
    }

    /**
     * Auto-promote a <em>high-severity</em> (critical / error) alert breach to a managed
     * {@link ObjectType#INCIDENT} so it enters the triage workflow, not only the alert feed — the same
     * signal→Incident wiring {@code ExpectationRoutes} already does for violated Expectations. Lower
     * severities stay alerts. Deduped across windows exactly like the ALERT object above (one active
     * INCIDENT per rule+pipeline), carrying the same breach attributes. Best-effort within the enclosing
     * try — a promotion failure never disturbs evaluation.
     */
    private void promoteToIncident(AlertRule rule, Alert alert, String pipeline, Map<String, String> attrs) {
        if (!isHighSeverity(rule.severity())) return;
        boolean active = objects.active(ObjectType.INCIDENT, pipeline).stream()
                .anyMatch(o -> rule.name().equals(o.attributes().get("rule")));
        if (active) return;
        objects.open(ObjectType.INCIDENT, rule.name() + " on " + pipeline, alert.message(),
                rule.severity(), pipeline, new LinkedHashMap<>(attrs));
    }

    /** Whether a rule severity warrants an Incident (critical / error) rather than staying an alert. */
    private static boolean isHighSeverity(String severity) {
        return severity != null
                && (severity.equalsIgnoreCase("critical") || severity.equalsIgnoreCase("error"));
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

    /**
     * Restrict {@code rows} to the ones matching the rule's {@code when} condition tree, via
     * {@link com.gamma.query.ConditionTree#filter} — the same evaluator Decision Rules use, so a
     * ledger row's fields ({@code status}, {@code duration_ms}, {@code rejected_count}, …) are
     * scoped identically to how the authoring UI would preview them.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> filterByWhen(Object when, List<Map<String, String>> rows) {
        List<Map<String, Object>> asObj = (List<Map<String, Object>>) (List<?>) rows;
        List<Map<String, Object>> matched = com.gamma.query.ConditionTree.filter(when, asObj);
        return (List<Map<String, String>>) (List<?>) matched;
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
        // Measure rules (no window) re-fire at most every 10 minutes while breached, like batch windows.
        long ms = (rule.window() == null || rule.batchWindow()) ? Duration.ofMinutes(10).toMillis()
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
