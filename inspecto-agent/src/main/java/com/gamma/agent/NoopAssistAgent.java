package com.gamma.agent;

import com.gamma.assist.spi.AssistAgent;
import com.gamma.service.CollectorService;

/**
 * Placeholder assist-agent provider (v3.0, milestone M0).
 *
 * <p>Its only job is to prove the cross-module SPI wiring: the optional
 * {@code file-processor-agent} module implements {@link AssistAgent} (defined in core) and
 * can be wired into a running {@link CollectorService}. It intentionally does nothing else.
 *
 * <p>It is <b>not</b> registered as a {@code ServiceLoader} provider yet (no
 * {@code META-INF/services} entry), so deployments behave exactly as before — auto-discovery
 * activates only once a real agent ships from M2. Real skills (explain-entity, nl-to-schedule,
 * suggest-config, kpi-to-sql, diagnose-and-alert) and the LangChain4j/Ollama wiring arrive in
 * later milestones; see {@code docs/v3-plan.md}.
 */
public final class NoopAssistAgent implements AssistAgent {

    private CollectorService service;

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public void init(CollectorService service) {
        this.service = service;
    }

    /** The host service this agent was wired to, or {@code null} before {@link #init}. */
    CollectorService boundService() {
        return service;
    }
}
