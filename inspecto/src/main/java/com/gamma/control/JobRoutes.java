package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.job.DbJobRunStore;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config-driven job routes ({@code /jobs*}, v2.8.0) plus the data-plane provenance reads
 * ({@code /provenance*}, T22/§11) which are projected by the same {@link JobService}. Covers the
 * job registry (list / per-job run history / manual trigger) and the optional DuckDB reporting
 * projection ({@code /jobs/metrics|runs|failures}, T27, 404 unless {@code -Djobs.backend} is set).
 * Extracted verbatim from {@link ControlApi}: identical routes, order, statuses and shapes.
 */
final class JobRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/jobs", (e, m) -> jobs(api).jobs());
        // Job CRUD (Scheduler write actions). Requires canAuthorWorkbench (W6; a no-op on Personal).
        // Persisted as <write-root>/jobs/<name>_job.toon; the write also hot-registers the job on the
        // live JobService (JobService.upsertJob/removeJob) so it takes effect without a restart.
        api.post("/jobs", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createJob(api, api.body(e))));
        api.put("/jobs/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> updateJob(api, ApiContext.name(m), api.body(e))));
        api.delete("/jobs/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> deleteJob(api, ApiContext.name(m))));
        // Job Type registry (R3, job-framework P2a): list + per-type descriptor (params/emits/artifacts)
        // that drives authoring forms. Fixed sub-paths under /jobs/, registered before the /jobs/{name}
        // regex routes (two segments; "types" never collides with a job name's /runs route).
        api.get("/jobs/types", (e, m) -> jobs(api).jobTypes().stream().map(JobTypeDescriptor::toMap).toList());
        api.get("/jobs/types/([^/]+)", (e, m) -> jobs(api).jobType(ApiContext.name(m))
                .map(JobTypeDescriptor::toMap)
                .orElseThrow(() -> new ApiException(404, "no job type '" + ApiContext.name(m) + "'")));
        // Job Pack inventory + explicit rescan (R8, job-framework P2c, §12/§14). Fixed sub-paths under
        // /jobs/, registered before the /jobs/{name} regex routes. Rescan is a canOperateRuns write
        // (reconciles the packs dir now instead of waiting on the watcher); every transition is audited
        // via job.pack.* signals on the ledger.
        api.get("/jobs/packs", (e, m) -> jobs(api).jobPacks());
        api.post("/jobs/packs/rescan", ApiContext.withCapability("canOperateRuns", (e, m) -> jobs(api).rescanPacks()));
        // T27 job-execution reporting (DuckDB projection; 404 unless -Djobs.backend is set). Fixed
        // sub-paths, registered before the /jobs/{name}/runs regex (single-segment, so no collision).
        api.get("/jobs/metrics", (e, m) -> jobRunStore(api).metrics(ApiContext.query(e, "job")));
        api.get("/jobs/runs", (e, m) -> jobRunStore(api).recentRuns(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50), ApiContext.query(e, "job")));
        api.get("/jobs/failures", (e, m) -> jobRunStore(api).failureTrend(ApiContext.parseIntOr(ApiContext.query(e, "days"), 30)));
        // W5 async: poll one run by id (the id returned by the 202 trigger below). Single-segment after
        // /jobs/runs/, so it never collides with the exact /jobs/runs or the /jobs/{name}/runs history route.
        api.get("/jobs/runs/([^/]+)", (e, m) -> runById(api, ApiContext.name(m)));
        api.get("/jobs/([^/]+)/runs", (e, m) -> jobs(api).runsFor(ApiContext.name(m)));
        // Structured Run Log for one run (R5, job-framework P0). More path segments than the history
        // route above and ends in /log, so the two never collide under full-match routing.
        api.get("/jobs/([^/]+)/runs/([^/]+)/log", (e, m) -> jobs(api).runLog(ApiContext.param(m, 2)));
        // Run Artifacts (R7, job-framework P1d, §10/§14): one run's recorded outputs, and the latest
        // successful run's outputs. Both end in fixed segments (/artifacts[/latest]) so they never
        // collide with the /runs history or /trigger routes under full-match routing.
        api.get("/jobs/([^/]+)/runs/([^/]+)/artifacts", (e, m) -> jobs(api).runArtifacts(ApiContext.param(m, 2)));
        api.get("/jobs/([^/]+)/artifacts/latest", (e, m) -> jobs(api).latestArtifacts(ApiContext.name(m)));
        // Requires canOperateRuns (W6; a no-op on Personal — no Subject is ever attached there).
        api.post("/jobs/([^/]+)/trigger", ApiContext.withCapability("canOperateRuns", (e, m) -> triggerJob(api, e, ApiContext.name(m))));

        // ── data-plane provenance (T22, §11): per-(node, relationship) record counts of a past flow run,
        // for painting quantities onto the PipelineGraph edges (Sankey). 404 unless -Dprovenance.backend is set. ──
        api.get("/provenance", (e, m) -> provenanceData(api, ApiContext.query(e, "flow"), ApiContext.query(e, "batch")));
        api.get("/provenance/batches", (e, m) -> provenanceBatches(api, ApiContext.query(e, "flow"), ApiContext.query(e, "limit")));
    }

    /**
     * {@code POST /jobs/{name}/trigger} — fire a job (async; jobs already run off the request thread). On the
     * v1 surface returns {@code 202} + {@code {runId,...}} + a {@code Location} to poll; the legacy surface keeps
     * its unchanged {@code 200 {job,status:"triggered"}} body. 404 if no such job.
     */
    private Object triggerJob(ApiContext api, HttpExchange e, String name) throws IOException {
        // Optional JSON body {"params":{...}} — explicit trigger args for this fire (job-framework §7.2 layer 1, P3a-2).
        Map<String, String> args = triggerArgs(api.body(e));
        // Optional ?dryRun=true (MNT-1): a preview fire — the Run reports impact, mutates nothing.
        boolean dryRun = "true".equalsIgnoreCase(ApiContext.query(e, "dryRun"));
        String runId = jobs(api).triggerRun(name, ApiContext.query(e, "actor"), args, dryRun)   // optional ?actor= attributes the fire (T32)
                .orElseThrow(() -> new ApiException(404, "no job named '" + name + "'"));
        if (ApiContext.v1(e)) {
            e.getResponseHeaders().set("Location", "/api/v1/jobs/runs/" + runId);
            return ApiContext.respondJson(e, 202,
                    Map.of("runId", runId, "job", name, "status", "running", "dryRun", dryRun));
        }
        return Map.of("job", name, "status", "triggered");
    }

    /** Extract the optional {@code params:{}} object from a trigger body as string-valued trigger args (§7.2 layer 1). */
    private static Map<String, String> triggerArgs(Map<String, Object> body) {
        if (!(body.get("params") instanceof Map<?, ?> p)) return Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : p.entrySet())
            if (en.getValue() != null) args.put(String.valueOf(en.getKey()), en.getValue().toString());
        return args;
    }

    /** {@code GET /jobs/runs/{runId}} — poll one run's status (W5); 404 once evicted or unknown. */
    private Object runById(ApiContext api, String runId) {
        JobRun r = jobs(api).runById(runId).orElseThrow(() -> new ApiException(404, "no run '" + runId + "'"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId());
        m.put("job", r.job());
        m.put("type", r.type());
        m.put("trigger", r.trigger());
        m.put("status", r.status());
        m.put("startedAt", r.startTime());
        m.put("finishedAt", r.endTime());
        m.put("durationMs", r.durationMs());
        m.put("message", r.message());
        return m;
    }

    /**
     * {@code GET /provenance?flow=&batch=} — the per-(node, relationship) record counts of one flow run (T22).
     * A consumer paints each {@code (nodeId, rel)} onto its outgoing {@code PipelineGraph} edge as the Sankey weight.
     * 400 if either param is missing, 404 when no provenance backend is configured.
     */
    private Object provenanceData(ApiContext api, String flow, String batch) {
        if (flow == null || flow.isBlank() || batch == null || batch.isBlank())
            throw new ApiException(400, "both 'flow' and 'batch' query params are required");
        return provenanceStore(api).query(flow, batch);
    }

    /** {@code GET /provenance/batches?flow=&limit=} — recent runs of a flow (newest first) to pick one to inspect. */
    private Object provenanceBatches(ApiContext api, String flow, String limit) {
        if (flow == null || flow.isBlank())
            throw new ApiException(400, "the 'flow' query param is required");
        return provenanceStore(api).batches(flow, ApiContext.parseIntOr(limit, 20));
    }

    /** The DuckDB data-plane provenance store (T21/T22), or a 404 when no backend is configured (-Dprovenance.backend). */
    private DbProvenanceStore provenanceStore(ApiContext api) {
        return jobs(api).provenanceStore().orElseThrow(() -> new ApiException(404,
                "provenance DB not enabled (set -Dprovenance.backend=duckdb)"));
    }

    /** {@code POST /jobs} — create a new scheduled job (write-root gated); 409 if the name exists. */
    private Object createJob(ApiContext api, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "job write");
        JobConfig c = parseJob(body);
        WriteGates.conflictIf(api.service().jobServiceOrCreate().jobs().stream()
                        .anyMatch(v -> v.name().equals(c.name())),
                "job '" + c.name() + "' already exists (use PUT to update)");
        persistJob(api, c);
        return c.toMap();
    }

    /** {@code PUT /jobs/{name}} — replace a job's config; 404 if unknown, 400 on a body/path name mismatch. */
    private Object updateJob(ApiContext api, String name, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "job write");
        JobService svc = jobs(api);
        if (svc.jobs().stream().noneMatch(v -> v.name().equals(name)))
            throw new ApiException(404, "no job named '" + name + "'");
        JobConfig c = parseJob(body);
        if (!name.equals(c.name())) throw new ApiException(400, "body 'name' must match the path id");
        persistJob(api, c);
        return c.toMap();
    }

    /** {@code DELETE /jobs/{name}} — remove a job's config + file; 404 if unknown. */
    private Object deleteJob(ApiContext api, String name) throws IOException {
        WriteGates.requireWriteRoot(api, "job write");
        JobService svc = jobs(api);
        if (svc.jobs().stream().noneMatch(v -> v.name().equals(name)))
            throw new ApiException(404, "no job named '" + name + "'");
        boolean removed = Files.deleteIfExists(jobFile(api, name));
        svc.removeJob(name);
        return Map.of("name", name, "deleted", true, "fileRemoved", removed);
    }

    /** Parse+validate a job body into a {@link JobConfig} via the same {@code job:}-section shape the
     *  TOON loader accepts (name/type/cron/... at top level of the body, mirroring {@code fromMap}). */
    private static JobConfig parseJob(Map<String, Object> body) {
        try {
            return JobConfig.fromMap(Map.of("job", body));
        } catch (RuntimeException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    /** Encode the config as a {@code job { … }} TOON doc, write it atomically, and hot-register it. */
    private void persistJob(ApiContext api, JobConfig c) throws IOException {
        Path target = jobFile(api, c.name());
        byte[] bytes = ConfigCodec.toToon(Map.of("job", c.toMap())).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".job-");
        api.service().jobServiceOrCreate().upsertJob(c);
    }

    /** The jailed {@code jobs/<name>_job.toon} path under the write root; 422 on an unsafe name, 403 on escape. */
    private Path jobFile(ApiContext api, String name) {
        String safe = WriteGates.safeName(name, "job name");
        Path root = api.writeRoot();
        return WriteGates.jail(root, root.resolve("jobs").resolve(safe + "_job.toon"), "resolved path");
    }

    /** The job registry, or a 404 when no jobs are registered on this service. */
    private JobService jobs(ApiContext api) {
        return api.service().jobService().orElseThrow(() -> new ApiException(404, "no jobs registered"));
    }

    /** The DuckDB job-run reporting store (T27), or a 404 when no backend is configured (-Djobs.backend). */
    private DbJobRunStore jobRunStore(ApiContext api) {
        return jobs(api).runStore().orElseThrow(() -> new ApiException(404,
                "job reporting DB not enabled (set -Djobs.backend=duckdb)"));
    }
}
