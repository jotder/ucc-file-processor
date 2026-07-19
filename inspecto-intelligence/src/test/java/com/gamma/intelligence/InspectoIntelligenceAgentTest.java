package com.gamma.intelligence;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.InlineArtifact;
import com.eoiagent.core.RunId;
import com.eoiagent.model.StubLlmGateway;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the AGT-5 (P0) session lifecycle, deterministic and offline via
 * {@link StubLlmGateway} — no live Ollama required (CPU-only CI).
 */
class InspectoIntelligenceAgentTest {

    private InspectoIntelligenceAgent open(StubLlmGateway gateway) {
        InspectoIntelligenceAgent agent = new InspectoIntelligenceAgent(gateway);
        agent.init(new CollectorService(List.of(), 3600, 1));
        agent.start();
        return agent;
    }

    @Test
    void openSessionThenAskReturnsTheScriptedAnswer() {
        StubLlmGateway gateway = StubLlmGateway.builder()
                .defaultReplyText("Ingestion runs nightly via the scheduler.")
                .build();
        InspectoIntelligenceAgent agent = open(gateway);
        try {
            AgentSessionResult session = agent.openSession(new AgentSessionRequest("analyst", Map.of()));
            assertNotNull(session.sessionId());

            AgentAskResult answer = agent.ask(session.sessionId(),
                    new AgentAskRequest("How does ingestion work?", Map.of()));
            assertEquals("TEXT", answer.kind());
            assertTrue(answer.text().contains("nightly"));
        } finally {
            agent.close();
        }
    }

    @Test
    void askOnAnUnknownSessionThrowsIllegalArgument() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> agent.ask("does-not-exist", new AgentAskRequest("hi", Map.of())));
        } finally {
            agent.close();
        }
    }

    @Test
    void openSessionAcceptsAKnownGoalKindAndRejectsAnUnknownOne() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        try {
            AgentSessionResult session = agent.openSession(
                    new AgentSessionRequest("analyst", Map.of(), "INVESTIGATION"));
            assertNotNull(session.sessionId(), "a valid goal kind opens a session");

            assertThrows(IllegalArgumentException.class,
                    () -> agent.openSession(new AgentSessionRequest("analyst", Map.of(), "NOT_A_KIND")));
        } finally {
            agent.close();
        }
    }

    @Test
    void closeIsCleanAndIdempotent() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        agent.close();
        agent.close(); // idempotent, no throw
    }

    @Test
    void recentCasesAndCaseByIdProjectTheStore() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        try {
            agent.caseStore().add(new com.gamma.intelligence.investigation.Case(
                    "case-1", "incident:1", Map.of("type", "pipeline.batch.failed"),
                    List.of(), List.of(), "open", List.of(), java.time.Instant.now()));

            List<Map<String, Object>> recent = agent.recentCases(50);
            assertEquals(1, recent.size());
            assertEquals("case-1", recent.get(0).get("id"));

            assertEquals("open", agent.caseById("case-1").orElseThrow().get("outcome"));
            assertTrue(agent.caseById("nope").isEmpty());
        } finally {
            agent.close();
        }
    }

    // S4: no live eoiagent session/tool produces an INLINE_ARTIFACT answer today (checked
    // DefaultAgentSession, eoiagent-examples, eoiagent-app-reference — no producer anywhere), so
    // these two tests construct the AgentAnswer/InlineArtifact directly and drive the package-private
    // toResult(...) test seam, bypassing the live AgentSession path entirely.

    @Test
    void toResultParsesAValidInlineArtifactIntoTheA2uiMap() {
        AgentAnswer answer = new AgentAnswer(AnswerKind.INLINE_ARTIFACT, "here's a chart",
                new InlineArtifact("application/vnd.a2ui+json", "title",
                        "{\"kind\":\"chart\",\"config\":{}}".getBytes(StandardCharsets.UTF_8), Map.of()),
                null, List.of(), new RunId("run-1"));

        AgentAskResult result = InspectoIntelligenceAgent.toResult(answer);

        assertEquals(Map.of("kind", "chart", "config", Map.of()), result.artifact());
    }

    @Test
    void toResultDropsAnArtifactThatFailsValidationRatherThanThrowing() {
        AgentAnswer wrongMimeType = new AgentAnswer(AnswerKind.INLINE_ARTIFACT, "here's a chart",
                new InlineArtifact("text/plain", "title",
                        "{\"kind\":\"chart\",\"config\":{}}".getBytes(StandardCharsets.UTF_8), Map.of()),
                null, List.of(), new RunId("run-2"));
        assertNull(InspectoIntelligenceAgent.toResult(wrongMimeType).artifact());

        AgentAnswer malformedJson = new AgentAnswer(AnswerKind.INLINE_ARTIFACT, "here's a chart",
                new InlineArtifact("application/vnd.a2ui+json", "title",
                        "not json".getBytes(StandardCharsets.UTF_8), Map.of()),
                null, List.of(), new RunId("run-3"));
        assertNull(InspectoIntelligenceAgent.toResult(malformedJson).artifact());

        AgentAnswer unknownKind = new AgentAnswer(AnswerKind.INLINE_ARTIFACT, "here's a chart",
                new InlineArtifact("application/vnd.a2ui+json", "title",
                        "{\"kind\":\"unknown-kind\"}".getBytes(StandardCharsets.UTF_8), Map.of()),
                null, List.of(), new RunId("run-4"));
        assertNull(InspectoIntelligenceAgent.toResult(unknownKind).artifact());
    }
}
