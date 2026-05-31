package com.gamma.agent;

import com.gamma.agent.model.ModelRouter;
import com.gamma.agent.skill.AssistContext;
import com.gamma.agent.skill.DocRetriever;
import com.gamma.agent.skill.ExplainEntitySkill;
import com.gamma.agent.skill.NlToScheduleSkill;
import com.gamma.agent.skill.SkillRegistry;
import com.gamma.agent.skill.SuggestConfigSkill;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.service.SourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * The real embedded assist agent (v3.3.0, M3) — the {@link AssistAgent} the optional
 * {@code file-processor-agent} module contributes via {@code ServiceLoader}. It captures the host
 * service's read-only handles in {@link #init}, builds the {@link SkillRegistry}, and dispatches
 * {@code POST /assist/{intent}} calls to the matching {@link com.gamma.agent.skill.Skill}.
 *
 * <h3>Lazy & abstain-safe</h3>
 * Construction and {@link #init} touch no network: the {@link ModelRouter}'s providers build their
 * model clients lazily and report {@link com.gamma.agent.model.ModelProvider#available() unavailable}
 * until a deployment turns the assist layer on. So discovering this agent in a CI/test process is
 * harmless — skills simply return "model unavailable" until Ollama is configured.
 *
 * <p>M3 ships exactly one skill, {@code explain-entity} (read-only). The agent holds no write
 * credential and performs no autonomous action.
 */
public final class UccAssistAgent implements AssistAgent {

    private static final Logger log = LoggerFactory.getLogger(UccAssistAgent.class);

    private final ModelRouter router;
    private final Consumer<AuditEvent> audit;
    private volatile SkillRegistry registry;
    private volatile AssistContext context;

    /** {@code ServiceLoader} entry point: model router resolved from the environment (abstain-safe). */
    public UccAssistAgent() {
        this(ModelRouter.fromEnvironment());
    }

    /** Test/embedder entry point: inject a router (e.g. a fake-backed one) for deterministic runs. */
    public UccAssistAgent(ModelRouter router) {
        this(router, null);
    }

    /** Inject a router and an audit sink (e.g. a capturing list in tests). A {@code null} sink logs only. */
    public UccAssistAgent(ModelRouter router, Consumer<AuditEvent> audit) {
        this.router = router;
        this.audit = audit;
    }

    @Override
    public String name() {
        return "ucc-assist";
    }

    @Override
    public void init(SourceService service) {
        DocRetriever docs = DocRetriever.fromEnvironment();
        this.context = new AssistContext(service.catalog(), service.reports(),
                service.statusStore(), docs, router);
        this.registry = new SkillRegistry(List.of(
                new ExplainEntitySkill(),
                new NlToScheduleSkill(),
                new SuggestConfigSkill()));
        log.info("Assist agent '{}' initialised: skills={}, docs={}, modelAvailable={}",
                name(), registry.intents(), docs.size(), router.anyAvailable());
    }

    @Override
    public AssistResult assist(AssistRequest request) {
        SkillRegistry r = registry;
        if (r == null) return AssistResult.unsupported(request.intent());
        long t0 = System.nanoTime();
        try {
            AssistResult result = r.dispatch(request, context);
            audit(request, result, t0);
            return result;
        } catch (RuntimeException e) {
            log.warn("assist intent '{}' failed", request.intent(), e);
            return AssistResult.unavailable(request.intent(), "assist error: " + e.getMessage());
        }
    }

    /** One audit event per call — agent-<em>suggested</em>, never applied (read-only in M3). */
    private void audit(AssistRequest req, AssistResult res, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        AuditEvent event = new AuditEvent(req.intent(), res.status(),
                res.citations().size(), ms, req.screenContext().keySet());
        log.info("[ASSIST] intent={} status={} citations={} ms={} ctxKeys={}",
                event.intent(), event.status(), event.citationCount(), event.durationMs(), event.contextKeys());
        if (audit != null) audit.accept(event);
    }
}
