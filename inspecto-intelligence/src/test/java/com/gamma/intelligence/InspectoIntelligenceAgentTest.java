package com.gamma.intelligence;

import com.eoiagent.model.StubLlmGateway;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

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
    void closeIsCleanAndIdempotent() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        agent.close();
        agent.close(); // idempotent, no throw
    }
}
