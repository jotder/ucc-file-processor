package com.gamma.job;

import com.gamma.etl.BatchEventBus;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.TagRule;
import com.gamma.signal.Severity;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code caserule.evaluate} Job Type: evaluates a saved {@link CaseRule}, grouping matching in-window
 * Incidents under a Case, and emits a {@code caserule.evaluate.completed} Signal with the counts. Mirrors
 * {@code ObjectServiceCaseRuleTest}'s example (a 3-threshold CRITICAL cluster) but drives the Job directly
 * with a capturing {@link JobContext}, and confirms the type is registered as a built-in.
 */
class CaseRuleEvalJobTest {

    private static CaseRule rule(int threshold) {
        return new CaseRule("crit-cluster", "Critical incident cluster",
                new TagRule.Filter("INCIDENT", null, null, "CRITICAL", null, null),
                threshold, 1440, "Pipeline / Ingest", "auto", 1);
    }

    private static OperationalObject incident(ObjectService svc, String title, String priority) {
        return svc.open(ObjectType.INCIDENT, title, "d", "HIGH", priority, null, null, "corr", Map.of());
    }

    private static JobConfig cfg(String rule) {
        return new JobConfig("nightly_cases", "caserule.evaluate", null, null, true, false,
                Map.of("rule", rule), null, null);
    }

    private static int caseCount(ObjectService svc) {
        return svc.query(ObjectQuery.builder().objectType(ObjectType.CASE).build()).size();
    }

    @Test
    void groupsMatchingIncidentsIntoACaseAndEmitsTheCounts() throws Exception {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerCaseRule(rule(3));
        incident(svc, "one", "CRITICAL");
        incident(svc, "two", "CRITICAL");
        incident(svc, "three", "CRITICAL");
        incident(svc, "low", "LOW");   // doesn't match the filter

        CapturingContext ctx = new CapturingContext(Map.of("rule", "crit-cluster"));
        JobResult result = new CaseRuleEvalJob(cfg("crit-cluster"), () -> svc).run(ctx);

        assertEquals("SUCCESS", result.status(), result.message());
        assertTrue(result.message().contains("(opened)"), result.message());
        assertEquals(1, caseCount(svc), "3 matches ≥ threshold ⇒ one Case opened");

        assertEquals("caserule.evaluate.completed", ctx.type.get());
        assertEquals(Severity.INFO, ctx.severity.get());
        Map<String, Object> p = ctx.payload.get();
        assertEquals("crit-cluster", p.get("rule"));
        assertEquals(3, p.get("matched"));
        assertEquals(3, p.get("grouped"));
        assertEquals(true, p.get("opened"));
        assertNotNull(p.get("caseId"));
    }

    @Test
    void reEvaluationIsIdempotentAndDoesNotCloneTheCase() throws Exception {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerCaseRule(rule(2));
        incident(svc, "a", "CRITICAL");
        incident(svc, "b", "CRITICAL");
        CaseRuleEvalJob job = new CaseRuleEvalJob(cfg("crit-cluster"), () -> svc);

        job.run(new CapturingContext(Map.of()));
        assertEquals(1, caseCount(svc), "threshold met ⇒ one Case");

        // a second fire with no new matches attaches nothing and never clones the Case
        CapturingContext second = new CapturingContext(Map.of());
        JobResult again = job.run(second);
        assertEquals(1, caseCount(svc), "idempotent — still one Case");
        assertEquals(0, second.payload.get().get("matched"), "already-grouped incidents are skipped");
        assertEquals(false, second.payload.get().get("opened"));
    }

    @Test
    void belowThresholdRaisesNoCase() throws Exception {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerCaseRule(rule(3));
        incident(svc, "one", "CRITICAL");
        incident(svc, "two", "CRITICAL");   // 2 < threshold 3

        CapturingContext ctx = new CapturingContext(Map.of());
        JobResult result = new CaseRuleEvalJob(cfg("crit-cluster"), () -> svc).run(ctx);

        assertTrue(result.message().contains("below threshold"), result.message());
        assertEquals(0, caseCount(svc));
        assertNull(ctx.payload.get().get("caseId"), "no case id in the signal when below threshold");
        assertEquals(2, ctx.payload.get().get("matched"));
    }

    @Test
    void unknownCaseRuleFailsClosed() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        CaseRuleEvalJob job = new CaseRuleEvalJob(cfg("ghost"), () -> svc);
        assertThrows(NoSuchElementException.class, () -> job.run(new CapturingContext(Map.of())));
    }

    @Test
    void missingObjectEngineFailsClosed() {
        CaseRuleEvalJob job = new CaseRuleEvalJob(cfg("crit-cluster"), () -> null);
        assertThrows(IllegalStateException.class, () -> job.run(new CapturingContext(Map.of())));
    }

    @Test
    void caseRuleEvaluateIsRegisteredAsABuiltInType() throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new BatchEventBus(), s, null,
                     "audit", null, null, "data")) {
            assertTrue(js.jobType("caserule.evaluate").isPresent(), "caserule.evaluate registered as a built-in");
            assertEquals("Case Rule Evaluation", js.jobType("caserule.evaluate").get().title());
        }
    }

    /** A {@link JobContext} that captures the one Signal the Job emits. */
    private static final class CapturingContext implements JobContext {
        final AtomicReference<String> type = new AtomicReference<>();
        final AtomicReference<Severity> severity = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        private final Map<String, String> params;

        CapturingContext(Map<String, String> params) { this.params = params; }

        @Override public String runId() { return "test-run"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return params; }
        @Override public Map<String, String> params() { return params; }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) {}
                @Override public void warn(String message, Object... kv) {}
                @Override public void error(String message, Throwable t, Object... kv) {}
            };
        }
        @Override public SignalEmitter signals() {
            return (t, sev, p) -> { type.set(t); severity.set(sev); payload.set(p); };
        }
        @Override public ArtifactRecorder artifacts() { return null; }
    }
}
