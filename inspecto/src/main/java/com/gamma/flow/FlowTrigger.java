package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.Map;

/**
 * <b>T13 — the entry-node trigger.</b> A flow's entry node (no inbound {@code data} edge — typically
 * {@code acquisition}/{@code adapter}) carries a {@code trigger:}; everything downstream is data-driven
 * (§3.6). This parses that config into a typed trigger and classifies which of the two schedulers (§3.8)
 * drives the flow — so the engine can route a flow to the loop scheduler vs the custom-function/event
 * scheduler from the graph alone, with no separate mechanism.
 *
 * <p>Trigger forms (§3.6):
 * <ul>
 *   <li>{@code {type: schedule, every: 60s}} — fixed-interval (today's poll) ⇒ {@link Scheduler#LOOP}.</li>
 *   <li>{@code {type: schedule, cron: "0 0/5 * * * *"}} — cron ⇒ {@link Scheduler#LOOP}.</li>
 *   <li>{@code {type: event, on: commit, from: flows/<id>}} / {@code {on: <EVENT_TYPE>}}, optional
 *       {@code coalesce: 30s} ⇒ {@link Scheduler#EVENT} (admitted under the non-overlapping lock, storms
 *       coalesced — see {@code TriggerCoalescer}).</li>
 *   <li>{@code {type: manual}} ⇒ {@link Scheduler#MANUAL} ({@code POST /flows/{id}/trigger}).</li>
 *   <li><b>absent</b> ⇒ {@link Kind#DEFAULT_POLL} — the service poll interval, so a lifted legacy
 *       pipeline behaves exactly as today.</li>
 * </ul>
 *
 * <p>Durations accept the engine's {@code Ns}/{@code Nm}/{@code Nh}/{@code Nd} suffix convention (a bare
 * number is seconds). The {@code cron} string is carried verbatim; the loop scheduler validates it via
 * {@code CronExpression} when it arms the schedule.
 */
@PublicApi(since = "4.3.0")
public record FlowTrigger(Kind kind, long everyMs, String cron, String on, String from, long coalesceMs) {

    /** The literal trigger shape declared on the entry node. */
    public enum Kind { SCHEDULE_INTERVAL, SCHEDULE_CRON, EVENT, MANUAL, DEFAULT_POLL }

    /** Which scheduler (§3.8 two-scheduler split) drives a flow carrying this trigger. */
    public enum Scheduler { LOOP, EVENT, MANUAL }

    /** The driving scheduler for this trigger. */
    public Scheduler scheduler() {
        return switch (kind) {
            case EVENT -> Scheduler.EVENT;
            case MANUAL -> Scheduler.MANUAL;
            case SCHEDULE_INTERVAL, SCHEDULE_CRON, DEFAULT_POLL -> Scheduler.LOOP;
        };
    }

    /** Whether an event storm should be debounced into one admitted run. */
    public boolean coalesces() {
        return coalesceMs > 0;
    }

    /** The trigger of {@code g}'s first entry node, or {@link Kind#DEFAULT_POLL} if it has no entry node. */
    public static FlowTrigger of(FlowGraph g) {
        var entries = g.entryNodes();
        return entries.isEmpty() ? defaultPoll() : of(entries.get(0));
    }

    /** Parse the {@code trigger:} config of an entry node. */
    @SuppressWarnings("unchecked")
    public static FlowTrigger of(FlowNode entry) {
        Object raw = entry.cfg("trigger");
        if (!(raw instanceof Map<?, ?>)) return defaultPoll();
        Map<String, Object> m = (Map<String, Object>) raw;

        String type = str(m, "type");
        long coalesce = millis(m.get("coalesce"));

        if (type == null || type.isBlank() || "schedule".equalsIgnoreCase(type)) {
            String cron = str(m, "cron");
            if (cron != null && !cron.isBlank())
                return new FlowTrigger(Kind.SCHEDULE_CRON, 0, cron, null, null, coalesce);
            Object every = m.get("every");
            if (every != null)
                return new FlowTrigger(Kind.SCHEDULE_INTERVAL, millis(every), null, null, null, coalesce);
            return new FlowTrigger(Kind.DEFAULT_POLL, 0, null, null, null, coalesce);
        }
        if ("event".equalsIgnoreCase(type))
            return new FlowTrigger(Kind.EVENT, 0, null, str(m, "on"), str(m, "from"), coalesce);
        if ("manual".equalsIgnoreCase(type))
            return new FlowTrigger(Kind.MANUAL, 0, null, null, null, coalesce);
        throw new IllegalArgumentException("unknown trigger type '" + type + "' on entry node '" + entry.id() + "'");
    }

    private static FlowTrigger defaultPoll() {
        return new FlowTrigger(Kind.DEFAULT_POLL, 0, null, null, null, 0);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    /** Parse a duration ({@code 60s}/{@code 5m}/{@code 2h}/{@code 1d}; a bare number is seconds) to millis; null ⇒ 0. */
    static long millis(Object v) {
        if (v == null) return 0;
        String s = v.toString().trim();
        if (s.isEmpty()) return 0;
        char last = s.charAt(s.length() - 1);
        if (Character.isDigit(last)) return Long.parseLong(s) * 1000L;       // bare number = seconds
        long n = Long.parseLong(s.substring(0, s.length() - 1).trim());
        return switch (Character.toLowerCase(last)) {
            case 's' -> n * 1000L;
            case 'm' -> n * 60_000L;
            case 'h' -> n * 3_600_000L;
            case 'd' -> n * 86_400_000L;
            default -> throw new IllegalArgumentException("bad duration '" + s + "'");
        };
    }
}
