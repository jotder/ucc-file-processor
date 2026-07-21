package com.gamma.intelligence.action;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@code runbook_operator} compound act tool (AGT-5 P3, autonomy L2): execute a <em>named,
 * seeded</em> runbook — a fixed, code-defined ordered sequence of the existing act tools — as one
 * approval-gated unit ({@code docs/superpower/embedded-intelligence-plan.md} §3 "a named, pre-approved
 * plan of gated tools", §4 {@code runbook_operator}).
 *
 * <p><b>Why one approval for the whole plan.</b> Runbooks are defined here in code, not by the model —
 * the model may only pick a runbook <em>name</em> and supply its parameters. So the operator approves a
 * plan they can see in full (the preview lists every resolved step), and the eoiagent gate fires once
 * for the {@code runbook_operator} call; the individual steps then run post-approval. This sidesteps
 * the framework's per-call parked-thread gate (nesting gated calls inside a gated call would deadlock),
 * while every underlying step still executes through the same audited control-plane route a UI caller
 * hits, carrying {@code X-Agent-Session} so each mutation audits as {@code actor=agent:<session>}.
 *
 * <p><b>Checkpointing + resume.</b> Execution is stepwise with a per-step log and halt-on-first-failure
 * (the result reports {@code haltedAtStep} and what completed). Progress is persisted to a
 * {@link RunbookRunStore} keyed by {@code (runbook, params)}, so a re-issued identical call
 * <em>resumes at the failed step</em> — already-succeeded (possibly non-idempotent) steps are skipped,
 * not re-run — and this survives a process restart when a write root is configured. A fully-completed
 * (terminal) run is not resumed: re-invoking it runs afresh from the start.
 */
public final class RunbookActions {

    public static final String TOOL_RUNBOOK_OPERATOR = "runbook_operator";

    /** One step of a runbook: an existing act tool + a builder from runbook params to that tool's args. */
    private record Step(String tool, Function<Map<String, Object>, Map<String, Object>> args) {}

    /** A seeded runbook: a named, fixed sequence of act-tool steps with the params it needs. */
    private record Runbook(String name, String description, List<String> requiredParams, List<Step> steps) {}

    /**
     * The seeded catalog (insertion-ordered). Each runbook chains existing, individually-audited act
     * tools into one operator-approved remediation. Keys are the lowercased runbook names.
     */
    private static final Map<String, Runbook> CATALOG = new LinkedHashMap<>();
    static {
        register(new Runbook("triage_and_replay",
                "Acknowledge a firing alert, then replay the failed batch that caused it",
                List.of("alertId", "pipeline", "batchId"),
                List.of(
                        new Step(OperationalActions.TOOL_ALERT_ACK,
                                p -> Map.of("id", p.get("alertId"))),
                        new Step(OperationalActions.TOOL_PIPELINE_RERUN,
                                p -> Map.of("pipeline", p.get("pipeline"), "batchId", p.get("batchId"))))));

        register(new Runbook("rollback_and_rerun",
                "Roll a component back to a known-good version, then re-run the job that consumes it",
                List.of("type", "id", "version", "job"),
                List.of(
                        new Step(ComponentActions.TOOL_COMPONENT_ROLLBACK,
                                p -> Map.of("type", p.get("type"), "id", p.get("id"), "version", p.get("version"))),
                        new Step(OperationalActions.TOOL_JOB_RUN,
                                p -> Map.of("job", p.get("job"))))));

        register(new Runbook("reschedule_and_trigger",
                "Change a job's cron schedule, then trigger one run now to validate it",
                List.of("job", "cron"),
                List.of(
                        new Step(OperationalActions.TOOL_SCHEDULE_APPLY,
                                p -> Map.of("job", p.get("job"), "cron", p.get("cron"))),
                        new Step(OperationalActions.TOOL_JOB_RUN,
                                p -> Map.of("job", p.get("job"))))));
    }

    private static void register(Runbook rb) {
        CATALOG.put(rb.name(), rb);
    }

    private RunbookActions() {
    }

    /** A human-readable catalog line for the tool description (name + required params). */
    public static String catalogSummary() {
        List<String> lines = new ArrayList<>();
        for (Runbook rb : CATALOG.values()) {
            lines.add(rb.name() + "(" + String.join(", ", rb.requiredParams()) + ")");
        }
        return String.join("; ", lines);
    }

    private static String names() {
        return String.join(", ", CATALOG.keySet());
    }

    // --- execution (post-approval, each step via the audited control plane) -----------------------

    /**
     * Run a named runbook to completion or first failure, attributed to {@code session}. Pre-flight
     * failures (unknown runbook, missing params) return {@code ok=false} having mutated nothing; once
     * execution starts, the result is {@code ok=true} carrying a per-step log and a {@code success}
     * flag (a mid-runbook step failure is a runbook <em>outcome</em>, not a tool-invocation error).
     */
    public static ToolResult execute(ControlPlaneClient client, ToolCall call, String session) {
        return execute(client, call, session, new RunbookRunStore()); // in-memory → no resume (legacy behaviour)
    }

    /**
     * Resume-aware execution (AGT-5 P3 mid-plan resume): consults {@code runs} for prior progress on the
     * same {@code (runbook, params)} and starts at the first not-yet-completed step, skipping the rest.
     * Progress is recorded after each successful step (and on completion), so a halted run re-issued
     * later — even across a restart — picks up where it stopped rather than re-running succeeded steps.
     */
    public static ToolResult execute(ControlPlaneClient client, ToolCall call, String session, RunbookRunStore runs) {
        String name = str(call, "runbook");
        if (name == null || name.isBlank()) return error("runbook is required (available: " + names() + ")");
        Runbook rb = CATALOG.get(name.trim().toLowerCase(Locale.ROOT));
        if (rb == null) return error("unknown runbook '" + name + "' (available: " + names() + ")");
        Map<String, Object> raw = mapArg(call, "params");
        Map<String, Object> params = raw == null ? Map.of() : raw;
        List<String> missing = rb.requiredParams().stream().filter(k -> blank(params.get(k))).toList();
        if (!missing.isEmpty()) {
            return error("runbook '" + rb.name() + "' requires params: " + missing);
        }

        String key = RunbookRunStore.key(rb.name(), params);
        int startIndex = Math.min(runs.resumeIndex(key), rb.steps().size()); // resume point (0 = fresh)

        List<Map<String, Object>> log = new ArrayList<>();
        for (int i = 0; i < startIndex; i++) { // steps a prior run already completed — skipped, not re-executed
            Step step = rb.steps().get(i);
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("step", i + 1);
            skipped.put("tool", step.tool());
            skipped.put("skipped", true);
            skipped.put("reason", "already completed in a prior run (resumed)");
            log.add(skipped);
        }

        int completed = startIndex; // already-succeeded steps from a prior run count as completed
        boolean success = true;
        for (int i = startIndex; i < rb.steps().size(); i++) {
            Step step = rb.steps().get(i);
            ToolCall stepCall = new ToolCall(step.tool(), step.args().apply(params), new RunId(session));
            ToolResult r = dispatch(step.tool(), client, stepCall, session);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", i + 1);
            entry.put("tool", step.tool());
            entry.put("ok", r.ok());
            if (r.ok()) entry.put("result", r.value());
            else entry.put("error", r.error());
            log.add(entry);
            if (!r.ok()) {
                success = false;
                break;
            }
            completed++;
            // checkpoint after each success; the last step's checkpoint is the terminal one written below
            if (i < rb.steps().size() - 1) runs.record(key, rb.name(), completed, false);
        }
        if (success) runs.record(key, rb.name(), completed, true); // terminal — a re-run starts fresh

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runbook", rb.name());
        result.put("success", success);
        result.put("completed", completed);
        result.put("total", rb.steps().size());
        if (startIndex > 0) result.put("resumedFromStep", startIndex + 1);
        if (!success) result.put("haltedAtStep", completed + 1);
        result.put("steps", log);
        result.put("actor", "agent:" + session);
        return ok(result);
    }

    /** Route a runbook step to the matching act-tool executor (each carries its own audit + safety gate). */
    private static ToolResult dispatch(String tool, ControlPlaneClient client, ToolCall stepCall, String session) {
        return switch (tool) {
            case ComponentActions.TOOL_COMPONENT_APPLY -> ComponentActions.apply(client, stepCall, session);
            case ComponentActions.TOOL_COMPONENT_ROLLBACK -> ComponentActions.rollback(client, stepCall, session);
            case OperationalActions.TOOL_JOB_RUN -> OperationalActions.jobRun(client, stepCall, session);
            case OperationalActions.TOOL_PIPELINE_RERUN -> OperationalActions.pipelineRerun(client, stepCall, session);
            case OperationalActions.TOOL_ALERT_ACK -> OperationalActions.alertAck(client, stepCall, session);
            case OperationalActions.TOOL_SCHEDULE_APPLY -> OperationalActions.scheduleApply(client, stepCall, session);
            default -> error("runbook step references unknown tool '" + tool + "'");
        };
    }

    // --- preview (read-only, shown to the operator before the single approval) --------------------

    /** The full resolved plan the operator reviews before approving the runbook. Read-only; never mutates. */
    public static Map<String, Object> preview(ToolCall call) {
        String name = str(call, "runbook");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "run-runbook");
        p.put("runbook", name);
        if (name == null || name.isBlank()) {
            p.put("error", "runbook is required");
            p.put("available", new ArrayList<>(CATALOG.keySet()));
            return p;
        }
        Runbook rb = CATALOG.get(name.trim().toLowerCase(Locale.ROOT));
        if (rb == null) {
            p.put("error", "unknown runbook '" + name + "'");
            p.put("available", new ArrayList<>(CATALOG.keySet()));
            return p;
        }
        Map<String, Object> raw = mapArg(call, "params");
        Map<String, Object> params = raw == null ? Map.of() : raw;
        List<String> missing = rb.requiredParams().stream().filter(k -> blank(params.get(k))).toList();

        p.put("description", rb.description());
        List<Map<String, Object>> plan = new ArrayList<>();
        for (int i = 0; i < rb.steps().size(); i++) {
            Step s = rb.steps().get(i);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", i + 1);
            step.put("tool", s.tool());
            step.put("mutating", true); // every seeded step is an act tool
            step.put("args", missing.isEmpty() ? s.args().apply(params) : Map.of());
            plan.add(step);
        }
        p.put("plan", plan);
        if (!missing.isEmpty()) p.put("missingParams", missing);
        return p;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static boolean blank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    private static String str(ToolCall call, String key) {
        Object v = args(call).get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(ToolCall call, String key) {
        Object v = args(call).get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
    }

    private static Map<String, Object> args(ToolCall call) {
        return call.arguments() == null ? Map.of() : call.arguments();
    }

    private static ToolResult ok(Object value) {
        return new ToolResult(true, value, null, Map.of());
    }

    private static ToolResult error(String message) {
        return new ToolResult(false, null, message, Map.of());
    }
}
