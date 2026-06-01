package com.gamma.agent;

import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.model.ModelRouter;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.etl.BatchEvent;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4: the agent emits one {@link AuditEvent} per call through the injectable sink — the audit trail
 * that distinguishes agent-<em>suggested</em> from (later) human-<em>applied</em>. M3 is read-only,
 * so every event is a suggestion.
 */
class AssistAuditTest {

    @Test
    void emitsOneAuditEventPerCall(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            UccAssistAgent agent = new UccAssistAgent(
                    ModelRouter.of(FakeModelProvider.canned("ok")), captured::add);
            agent.init(svc);

            agent.assist(new AssistRequest("explain-entity",
                    Map.of("entityType", "table", "id", "event:mini_etl/mini"), Map.of(), "explain"));

            assertEquals(1, captured.size(), "exactly one audit event");
            AuditEvent e = captured.get(0);
            assertEquals("explain-entity", e.intent());
            assertEquals(AssistResult.Status.OK, e.status());
            assertTrue(e.citationCount() >= 1, "grounded answer cited at least the node");
            assertTrue(e.contextKeys().contains("id"), "context keys recorded (not values)");
        }
    }

    @Test
    void nlToScheduleDraftIsAuditedAsOk(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            UccAssistAgent agent = new UccAssistAgent(ModelRouter.of(FakeModelProvider.canned(
                    "{\"name\":\"j\",\"cron\":\"0 2 * * *\",\"job_type\":\"maintenance\"}")), captured::add);
            agent.init(svc);

            AssistResult res = agent.assist(new AssistRequest("nl-to-schedule",
                    Map.of(), Map.of(), "every day at 2am"));
            assertEquals(AssistResult.Status.OK, res.status());
            assertNull(res.applyVia(), "draft-only: the audited call carries no write endpoint");

            assertEquals(1, captured.size(), "exactly one suggestion event for the draft");
            assertEquals("nl-to-schedule", captured.get(0).intent());
            assertEquals(AssistResult.Status.OK, captured.get(0).status());
        }
    }

    @Test
    void suggestConfigDraftIsAuditedAsOk(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            UccAssistAgent agent = new UccAssistAgent(ModelRouter.of(FakeModelProvider.canned(
                    "{\"fields\":[{\"name\":\"job.name\",\"value\":\"nightly\",\"rationale\":\"x\",\"confidence\":\"high\"},"
                            + "{\"name\":\"job.cron\",\"value\":\"0 2 * * *\",\"rationale\":\"x\",\"confidence\":\"high\"},"
                            + "{\"name\":\"job.type\",\"value\":\"maintenance\",\"rationale\":\"x\",\"confidence\":\"low\"}]}")),
                    captured::add);
            agent.init(svc);

            AssistResult res = agent.assist(new AssistRequest("suggest-config",
                    Map.of("configType", "job"), Map.of(), null));
            assertEquals(AssistResult.Status.OK, res.status());
            assertNull(res.applyVia(), "draft-only: the audited call carries no write endpoint");

            assertEquals(1, captured.size(), "exactly one suggestion event for the draft");
            assertEquals("suggest-config", captured.get(0).intent());
            assertEquals(AssistResult.Status.OK, captured.get(0).status());
        }
    }

    @Test
    void diagnoseAndAlertDraftIsAuditedAsOk(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            UccAssistAgent agent = new UccAssistAgent(ModelRouter.of(FakeModelProvider.canned(
                    "{\"name\":\"errs\",\"metric\":\"error_rate\",\"comparator\":\"gt\",\"threshold\":0.05,"
                            + "\"window\":\"1h\",\"severity\":\"WARNING\"}")), captured::add);
            agent.init(svc);

            AssistResult res = agent.assist(new AssistRequest("diagnose-and-alert",
                    Map.of(), Map.of(), "warn when error rate exceeds 5%"));
            assertEquals(AssistResult.Status.OK, res.status());
            assertNull(res.applyVia(), "draft-only: the audited call carries no write endpoint");

            assertEquals(1, captured.size(), "exactly one suggestion event for the draft");
            assertEquals("diagnose-and-alert", captured.get(0).intent());
            assertEquals(AssistResult.Status.OK, captured.get(0).status());
        }
    }

    /** Event-driven: a FAILED batch is diagnosed off-thread and audited (keys, not values). */
    @Test
    void failedBatchEventIsDiagnosedAndAudited(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            UccAssistAgent agent = new UccAssistAgent(
                    ModelRouter.of(FakeModelProvider.canned("Likely a schema drift; reconcile selectors.")),
                    captured::add);
            agent.init(svc);   // subscribes the failure reactor to the bus

            svc.eventBus().publish(new BatchEvent("MINI_ETL", "B1", "FAILED", List.of(),
                    0, 10L, 1, "schema selector mismatch", "bad.csv", 3));

            // Diagnosis happens on the reactor's executor — await the audit event.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (captured.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);

            assertEquals(1, captured.size(), "one audit event for the diagnosis");
            AuditEvent e = captured.get(0);
            assertEquals("diagnose-and-alert", e.intent());
            assertEquals(AssistResult.Status.OK, e.status());
            assertTrue(e.contextKeys().contains("batchId") && e.contextKeys().contains("severity"),
                    "records context keys, not data-plane values: " + e.contextKeys());

            // And the diagnosis is queryable via the agent's SPI seam.
            assertEquals(1, agent.recentDiagnoses(10).size());
            assertEquals("B1", agent.recentDiagnoses(10).get(0).batchId());
        }
    }

    /** A SUCCESS commit must not be diagnosed — only failures are. */
    @Test
    void successfulBatchEventIsNotDiagnosed(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            UccAssistAgent agent = new UccAssistAgent(
                    ModelRouter.of(FakeModelProvider.canned("ignored")), captured::add);
            agent.init(svc);

            svc.eventBus().publish(new BatchEvent("MINI_ETL", "ok1", "SUCCESS", List.of(), 5, 10L, 0));
            Thread.sleep(200);   // give any (erroneous) async work a chance to run

            assertEquals(0, captured.size(), "SUCCESS commits are not diagnosed");
            assertEquals(0, agent.recentDiagnoses(10).size());
        }
    }

    @Test
    void unknownIntentIsAuditedAsUnsupported(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            UccAssistAgent agent = new UccAssistAgent(
                    ModelRouter.of(FakeModelProvider.canned("ok")), captured::add);
            agent.init(svc);
            AssistResult res = agent.assist(new AssistRequest("no-such", Map.of(), Map.of(), null));
            assertEquals(AssistResult.Status.UNSUPPORTED, res.status());
            assertEquals(AssistResult.Status.UNSUPPORTED, captured.get(0).status());
        }
    }
}
