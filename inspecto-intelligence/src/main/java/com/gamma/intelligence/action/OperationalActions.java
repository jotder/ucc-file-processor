package com.gamma.intelligence.action;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.gamma.service.CollectorService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The execution + preview logic for the four P3 <em>operational</em> act tools (autonomy L2), shared by
 * the tool bodies in {@code InspectoTools} and the dry-run previewer in {@link AgentApprovals}. Where
 * {@link ComponentActions} mutates the config registry, these drive the running system's operational
 * verbs — always through the <em>same</em> audited, fail-closed control-plane routes a UI/API caller
 * hits ({@code docs/superpower/embedded-intelligence-plan.md} §3 "Act", §0.2 "no private backdoor"),
 * never an in-process shortcut. Every call carries {@code X-Agent-Session} so the write audits as
 * {@code actor=agent:<session>}.
 *
 * <ul>
 *   <li>{@code job_run} — trigger a job on demand: {@code POST /jobs/{name}/trigger}.</li>
 *   <li>{@code pipeline_rerun} — replay a committed batch of a pipeline (the RCA remediation verb):
 *       {@code POST /runs/{pipeline}/reprocess} with {@code {batchId}}.</li>
 *   <li>{@code alert_ack} — acknowledge an operational alert (an Alert-Center object,
 *       {@code OPEN→ACKNOWLEDGED}): {@code POST /objects/{id}/ack}.</li>
 *   <li>{@code schedule_apply} — change a job's cron schedule: {@code POST /jobs/{name}/reschedule}
 *       with {@code {cron}} (requires a write root, like every config-persisting route).</li>
 * </ul>
 *
 * <p>The {@link #preview} the operator reviews is read-only — an action summary plus whatever live,
 * cheap-to-read state ({@link CollectorService}) helps the human decide (does the target exist, is the
 * pipeline paused). It never touches the mutating path.
 */
public final class OperationalActions {

    public static final String TOOL_JOB_RUN = "job_run";
    public static final String TOOL_PIPELINE_RERUN = "pipeline_rerun";
    public static final String TOOL_ALERT_ACK = "alert_ack";
    public static final String TOOL_SCHEDULE_APPLY = "schedule_apply";

    private OperationalActions() {
    }

    // --- execution (post-approval, via the audited control plane) ---------------------------------

    /** {@code job_run}: trigger a job on demand ({@code POST /jobs/{name}/trigger}), attributed to {@code session}. */
    public static ToolResult jobRun(ControlPlaneClient client, ToolCall call, String session) {
        String job = str(call, "job");
        if (job == null || job.isBlank()) return error("job is required");
        Map<String, Object> params = mapArg(call, "params");
        Object body = params == null || params.isEmpty() ? null : Map.of("params", params);

        ControlPlaneClient.Response r = client.exchange("POST",
                "/jobs/" + job + "/trigger", body, null, session);
        if (r.status() < 0) return error(r.raw());
        if (!r.ok()) return error("job_run failed: status " + r.status() + " — " + r.raw());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggered", true);
        result.put("job", job);
        // The control plane echoes runId/status (v1 202) or {status:"triggered"} (root); surface both.
        if (r.body().get("runId") != null) result.put("runId", r.body().get("runId"));
        if (r.body().get("status") != null) result.put("status", r.body().get("status"));
        result.put("actor", "agent:" + session);
        return ok(result);
    }

    /** {@code pipeline_rerun}: reprocess a committed batch ({@code POST /runs/{pipeline}/reprocess}). */
    public static ToolResult pipelineRerun(ControlPlaneClient client, ToolCall call, String session) {
        String pipeline = str(call, "pipeline");
        String batchId = str(call, "batchId");
        if (pipeline == null || pipeline.isBlank()) return error("pipeline is required");
        if (batchId == null || batchId.isBlank()) return error("batchId is required");

        ControlPlaneClient.Response r = client.exchange("POST",
                "/runs/" + pipeline + "/reprocess", Map.of("batchId", batchId), null, session);
        if (r.status() < 0) return error(r.raw());
        if (!r.ok()) return error("pipeline_rerun failed: status " + r.status() + " — " + r.raw());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reprocessed", true);
        result.put("pipeline", pipeline);
        result.put("batchId", batchId);
        result.put("actor", "agent:" + session);
        return ok(result);
    }

    /** {@code alert_ack}: acknowledge an operational alert object ({@code POST /objects/{id}/ack}). */
    public static ToolResult alertAck(ControlPlaneClient client, ToolCall call, String session) {
        String id = str(call, "id");
        if (id == null || id.isBlank()) return error("id is required");

        // Actor rides X-Agent-Session (→ actor=agent:<session>); no body needed.
        ControlPlaneClient.Response r = client.exchange("POST",
                "/objects/" + id + "/ack", null, null, session);
        if (r.status() < 0) return error(r.raw());
        if (!r.ok()) return error("alert_ack failed: status " + r.status() + " — " + r.raw());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acknowledged", true);
        result.put("id", id);
        if (r.body().get("status") != null) result.put("status", r.body().get("status"));
        result.put("actor", "agent:" + session);
        return ok(result);
    }

    /** {@code schedule_apply}: change a job's cron ({@code POST /jobs/{name}/reschedule}), attributed to {@code session}. */
    public static ToolResult scheduleApply(ControlPlaneClient client, ToolCall call, String session) {
        String job = str(call, "job");
        String cron = str(call, "cron");
        if (job == null || job.isBlank()) return error("job is required");
        if (cron == null || cron.isBlank()) return error("cron is required");

        ControlPlaneClient.Response r = client.exchange("POST",
                "/jobs/" + job + "/reschedule", Map.of("cron", cron), null, session);
        if (r.status() < 0) return error(r.raw());
        if (!r.ok()) return error("schedule_apply failed: status " + r.status() + " — " + r.raw());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rescheduled", true);
        result.put("job", job);
        result.put("cron", cron);
        result.put("actor", "agent:" + session);
        return ok(result);
    }

    // --- preview (read-only, shown to the operator before approval) -------------------------------

    /** The dry-run summary the operator reviews before approving. Read-only; never mutates. */
    public static Map<String, Object> preview(CollectorService service, ToolCall call) {
        return switch (call.toolName()) {
            case TOOL_JOB_RUN -> previewJobRun(service, call);
            case TOOL_PIPELINE_RERUN -> previewPipelineRerun(service, call);
            case TOOL_ALERT_ACK -> previewAlertAck(call);
            case TOOL_SCHEDULE_APPLY -> previewScheduleApply(service, call);
            default -> Map.of();
        };
    }

    private static Map<String, Object> previewJobRun(CollectorService service, ToolCall call) {
        String job = str(call, "job");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "run-job");
        p.put("target", job);
        Map<String, Object> params = mapArg(call, "params");
        if (params != null && !params.isEmpty()) p.put("params", params);
        if (job == null || job.isBlank()) {
            p.put("error", "job is required");
            return p;
        }
        p.put("jobExists", jobExists(service, job));
        return p;
    }

    private static Map<String, Object> previewPipelineRerun(CollectorService service, ToolCall call) {
        String pipeline = str(call, "pipeline");
        String batchId = str(call, "batchId");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "reprocess-batch");
        p.put("target", pipeline);
        p.put("batchId", batchId);
        if (pipeline == null || pipeline.isBlank() || batchId == null || batchId.isBlank()) {
            p.put("error", "pipeline and batchId are required");
            return p;
        }
        pipelineView(service, pipeline).ifPresentOrElse(
                v -> {
                    p.put("pipelineExists", true);
                    p.put("paused", v.paused());
                },
                () -> p.put("pipelineExists", false));
        return p;
    }

    private static Map<String, Object> previewAlertAck(ToolCall call) {
        String id = str(call, "id");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "acknowledge-alert");
        p.put("target", id);
        if (id == null || id.isBlank()) p.put("error", "id is required");
        return p;
    }

    private static Map<String, Object> previewScheduleApply(CollectorService service, ToolCall call) {
        String job = str(call, "job");
        String cron = str(call, "cron");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", "reschedule-job");
        p.put("target", job);
        p.put("newCron", cron);
        if (job == null || job.isBlank() || cron == null || cron.isBlank()) {
            p.put("error", "job and cron are required");
            return p;
        }
        p.put("jobExists", jobExists(service, job));
        return p;
    }

    // --- read-only live-state helpers (best-effort; a lookup failure never blocks the preview) -----

    private static boolean jobExists(CollectorService service, String job) {
        if (service == null) return false;
        try {
            return service.jobService()
                    .map(js -> js.jobs().stream().anyMatch(j -> job.equalsIgnoreCase(j.name())))
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Optional<CollectorService.PipelineView> pipelineView(CollectorService service, String pipeline) {
        if (service == null) return Optional.empty();
        try {
            return service.pipelines().stream()
                    .filter(v -> v.name().equalsIgnoreCase(pipeline)).findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

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
