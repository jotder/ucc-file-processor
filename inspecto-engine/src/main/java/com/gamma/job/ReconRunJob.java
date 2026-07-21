package com.gamma.job;

import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.ReconConfigLoader;
import com.gamma.query.ReconService;
import com.gamma.signal.Severity;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code recon.run} Job Type (DAT-7 "Ops" follow-up) — runs a saved {@code reconciliation} component
 * over its Datasets and emits a {@code recon.run.completed} Signal carrying the Break counts, so a run can
 * be scheduled (a {@code cron:} Job) instead of only triggered from the Board. It builds the exact same
 * {@link ReconService.Spec} the interactive {@code POST /recon/run} route does (via
 * {@link ReconConfigLoader}), so a scheduled reconciliation reconciles identically to a manual one.
 *
 * <p>Signal severity is {@code WARNING} when any Break exists, else {@code INFO} — an honest ledger fact a
 * future Alert Rule can watch. A breach ({@code breaks > 0}) also opens a managed {@link ObjectType#INCIDENT}
 * (deduped to one open Incident per reconciliation), the same signal→Incident wiring the alert path does, so
 * a scheduled reconciliation's breaks enter the triage workflow rather than only the signal ledger. The
 * {@link ObjectService} is resolved through a supplier (it is wired onto the {@code JobService} after this
 * built-in is constructed); a {@code null} supplier value leaves the Job signal-only.
 *
 * <p>Follows the built-in convention of constructor-injected {@code dataDir} plus reading the component
 * registry from {@code -Dassist.write.root} at run time (as {@link MaterializeTask}/{@code ReportJob}).
 */
final class ReconRunJob implements Job {

    /** Board grain-row cap (MAX_LIMIT parity with ReconRoutes); the Break summary is exact regardless. */
    private static final int GRAIN_LIMIT = 5_000;

    private final JobConfig cfg;
    private final String dataDir;
    /** Live view of this space's {@link ObjectService} (wired post-construction on the JobService); its value
     *  is {@code null} until wired and on the bare-JobService test constructors — then the Job stays signal-only. */
    private final Supplier<ObjectService> objects;

    ReconRunJob(JobConfig cfg, String dataDir, Supplier<ObjectService> objects) {
        this.cfg = cfg;
        this.dataDir = dataDir;
        this.objects = objects;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "recon.run"; }

    /** {@code recon.run} always runs with a {@link JobContext} (it emits a Signal). */
    @Override public JobResult run() {
        throw new UnsupportedOperationException("recon.run requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        long t0 = System.nanoTime();
        String wr = System.getProperty("assist.write.root");
        if (wr == null || wr.isBlank())
            throw new IllegalStateException("recon.run needs -Dassist.write.root (the component registry)");
        if (dataDir == null || dataDir.isBlank())
            throw new IllegalStateException("recon.run needs a data root (-Ddata.dir / space dataDir)");
        Path writeRoot = Path.of(wr);
        Path dataRoot = Path.of(dataDir);
        String reconId = cfg.require("reconciliation");

        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        Map<String, Object> config = store.get("reconciliation", reconId)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException("unknown reconciliation '" + reconId + "'"));

        ReconService.Spec spec = ReconConfigLoader.buildSpec(config, dsId -> {
            Map<String, Object> ds = store.get("dataset", dsId)
                    .map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("unknown dataset '" + dsId + "'"));
            return DatasetRelation.relationSql(ds, dataRoot, views);
        });

        ReconService.RunResult r = ReconService.run(spec, GRAIN_LIMIT);
        Map<String, Object> byType = r.summary().get("byType") instanceof Map<?, ?> bt ? cast(bt) : Map.of();
        long missingLeft = num(byType.get("missing_left"));
        long missingRight = num(byType.get("missing_right"));
        long valueBreak = num(byType.get("value_break"));
        long breaks = missingLeft + missingRight + valueBreak;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reconciliation", reconId);
        payload.put("missingLeft", missingLeft);
        payload.put("missingRight", missingRight);
        payload.put("valueBreak", valueBreak);
        payload.put("breaks", breaks);
        payload.put("matchedKeys", r.summary().get("matchedKeys"));
        ctx.signals().emit("recon.run.completed", breaks > 0 ? Severity.WARN : Severity.INFO, payload);
        ctx.log().info("reconciliation complete", "reconciliation", reconId, "breaks", breaks);
        if (breaks > 0) openIncident(ctx, reconId, missingLeft, missingRight, valueBreak, breaks);

        return JobResult.ok("recon.run '" + reconId + "': " + breaks + " break(s) ("
                + missingLeft + " missing-left, " + missingRight + " missing-right, " + valueBreak + " value-break)",
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * Open a managed {@link ObjectType#INCIDENT} for a reconciliation breach, deduped to one open Incident
     * per reconciliation (correlationId = the reconciliation id) so a nightly schedule doesn't hand the
     * operator a clone every run. No-op when no {@link ObjectService} is wired. Best-effort: a failure here
     * is logged to the run and never fails the run itself (the signal is already the durable ledger fact).
     */
    private void openIncident(JobContext ctx, String reconId, long missingLeft, long missingRight,
                              long valueBreak, long breaks) {
        ObjectService svc = objects == null ? null : objects.get();
        if (svc == null) return;
        try {
            if (!svc.active(ObjectType.INCIDENT, reconId).isEmpty()) return;   // one open Incident per reconciliation
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("reconciliation", reconId);
            attrs.put("breaks", String.valueOf(breaks));
            attrs.put("missingLeft", String.valueOf(missingLeft));
            attrs.put("missingRight", String.valueOf(missingRight));
            attrs.put("valueBreak", String.valueOf(valueBreak));
            svc.open(ObjectType.INCIDENT, "Reconciliation " + reconId + " — " + breaks + " break(s)",
                    breaks + " break(s): " + missingLeft + " missing-left, " + missingRight
                            + " missing-right, " + valueBreak + " value-break",
                    "WARNING", reconId, attrs);
        } catch (RuntimeException e) {
            ctx.log().warn("could not open Incident for reconciliation breach", "reconciliation", reconId,
                    "error", e.getMessage());
        }
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
