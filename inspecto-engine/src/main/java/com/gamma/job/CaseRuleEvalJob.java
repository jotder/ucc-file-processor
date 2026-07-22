package com.gamma.job;

import com.gamma.ops.ObjectService;
import com.gamma.signal.Severity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code caserule.evaluate} Job Type (C5 "Ops" follow-up) — runs a saved {@link com.gamma.ops.tag.CaseRule}
 * on a schedule, grouping matching in-window Incidents under a Case, and emits a
 * {@code caserule.evaluate.completed} Signal carrying the match/group counts. It wraps the exact same
 * {@link ObjectService#evaluateCaseRule(String)} step the interactive {@code POST /cases/rules/{name}/evaluate}
 * route drives, so a scheduled evaluation groups identically to a manual one — turning the Alert → Incident →
 * Case chain's tail into a {@code cron:} Job instead of a Board-only action.
 *
 * <p>Evaluation is idempotent by design (already-grouped Incidents are skipped; later matches attach to the
 * same still-open rule-raised Case via {@code attributes.raisedByRule}), which is exactly what a repeated cron
 * fire needs — a nightly schedule attaches new matches rather than cloning a Case each run.
 *
 * <p>Unlike {@code recon.run} (where the {@link ObjectService} only adds an optional Incident promotion on top
 * of a self-contained computation), the Object Engine <em>is</em> the work here — so a missing {@link ObjectService}
 * fails the Run closed rather than degrading to signal-only. The service is resolved through a supplier (it is
 * wired onto the {@code JobService} after this built-in is constructed).
 */
final class CaseRuleEvalJob implements Job {

    private final JobConfig cfg;
    /** Live view of this space's {@link ObjectService} (wired post-construction on the JobService); {@code null}
     *  until wired and on the bare-JobService test constructors — then the Run fails closed. */
    private final Supplier<ObjectService> objects;

    CaseRuleEvalJob(JobConfig cfg, Supplier<ObjectService> objects) {
        this.cfg = cfg;
        this.objects = objects;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "caserule.evaluate"; }

    /** {@code caserule.evaluate} always runs with a {@link JobContext} (it emits a Signal). */
    @Override public JobResult run() {
        throw new UnsupportedOperationException("caserule.evaluate requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        long t0 = System.nanoTime();
        String ruleName = cfg.require("rule");
        ObjectService svc = objects == null ? null : objects.get();
        if (svc == null)
            throw new IllegalStateException("caserule.evaluate needs the space Object Engine (JobService.objects not wired)");

        ObjectService.CaseRuleEvaluation e = svc.evaluateCaseRule(ruleName);   // throws if the rule is unknown

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule", ruleName);
        payload.put("matched", e.matched());
        payload.put("grouped", e.grouped());
        payload.put("opened", e.opened());
        if (e.caseId() != null) payload.put("caseId", e.caseId());
        ctx.signals().emit("caserule.evaluate.completed", Severity.INFO, payload);
        ctx.log().info("case rule evaluated", "rule", ruleName, "matched", e.matched(),
                "grouped", e.grouped(), "case", e.caseId());

        String msg = e.caseId() == null
                ? "caserule.evaluate '" + ruleName + "': " + e.matched() + " match(es), below threshold — no case"
                : "caserule.evaluate '" + ruleName + "': " + e.grouped() + " grouped into case " + e.caseId()
                        + (e.opened() ? " (opened)" : " (existing)");
        return JobResult.ok(msg, (System.nanoTime() - t0) / 1_000_000L);
    }
}
