package com.gamma.agent;

import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.model.ModelRouter;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
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
