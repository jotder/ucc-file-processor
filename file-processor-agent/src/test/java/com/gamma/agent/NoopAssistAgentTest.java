package com.gamma.agent;

import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the v3.0 (M0) cross-module assist SPI wiring end-to-end: the optional agent module
 * implements {@code AssistAgent} (from core) and {@link SourceService} accepts and binds it.
 * No engine work, no I/O — just the injection point.
 */
class NoopAssistAgentTest {

    @Test
    void wiresIntoSourceServiceViaTheSpi() {
        SourceService svc = new SourceService(List.of(), 60, 1);
        assertFalse(svc.assistAgent().isPresent(), "no agent before registration");

        NoopAssistAgent agent = new NoopAssistAgent();
        svc.registerAgent(agent);

        assertTrue(svc.assistAgent().isPresent(), "agent present after registration");
        assertEquals("noop", svc.assistAgent().get().name());
        assertSame(svc, agent.boundService(), "init() received the host service");
    }

    @Test
    void secondRegistrationIsIgnored() {
        SourceService svc = new SourceService(List.of(), 60, 1);
        NoopAssistAgent first = new NoopAssistAgent();
        NoopAssistAgent second = new NoopAssistAgent();

        svc.registerAgent(first);
        svc.registerAgent(second);   // ignored — one agent per service

        assertSame(first, svc.assistAgent().orElseThrow(), "first registration wins");
    }
}
