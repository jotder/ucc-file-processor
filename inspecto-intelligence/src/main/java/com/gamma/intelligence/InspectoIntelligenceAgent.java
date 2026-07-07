package com.gamma.intelligence;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.Citation;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;
import com.gamma.intelligence.pack.InspectoPack;
import com.gamma.intelligence.spi.IntelligenceAgent;
import com.gamma.service.SourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link IntelligenceAgent} provider (AGT-5, P0): assembles the {@link InspectoPack} on the
 * eoiagent platform and hosts multi-turn {@link AgentSession}s keyed by a server-issued session id
 * (the eoiagent core has no session-id concept of its own — the host owns that mapping).
 */
public final class InspectoIntelligenceAgent implements IntelligenceAgent {

    private static final Logger log = LoggerFactory.getLogger(InspectoIntelligenceAgent.class);

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final LlmGateway gatewayOverride;
    private SourceService service;
    private InspectoPack pack;
    private AgentPlatform platform;

    /** Discovered/registered via {@link SourceService}; builds its gateway from {@link GatewayFactory}. */
    public InspectoIntelligenceAgent() {
        this(null);
    }

    /** Test seam: an explicit gateway (e.g. a deterministic {@code StubLlmGateway}) skips {@link GatewayFactory}. */
    InspectoIntelligenceAgent(LlmGateway gatewayOverride) {
        this.gatewayOverride = gatewayOverride;
    }

    @Override
    public String name() {
        return "inspecto-intelligence";
    }

    @Override
    public void init(SourceService service) {
        this.service = service;
    }

    @Override
    public void start() {
        pack = new InspectoPack(service);
        platform = new PlatformBuilder()
                .pack(pack)
                .llmGateway(gatewayOverride != null ? gatewayOverride : GatewayFactory.build())
                .start();
        log.info("Intelligence platform assembled: {} v{}", platform.pack().name(), platform.pack().version());
    }

    @Override
    public AgentSessionResult openSession(AgentSessionRequest request) {
        Role role = pack.policyProfile().mapRole(request.role());
        SessionRequest sessionRequest = new SessionRequest(new UserId(UUID.randomUUID().toString()),
                role, DeploymentProfile.OFFLINE, toPageContext(request.page()), Map.of());
        AgentSession session = platform.agentService().open(sessionRequest);
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, session);
        return new AgentSessionResult(sessionId, Instant.now().toString());
    }

    @Override
    public AgentAskResult ask(String sessionId, AgentAskRequest request) {
        AgentSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("unknown intelligence session: '" + sessionId + "'");
        }
        AgentAnswer answer = session.ask(new UserMessage(request.question(), toPageContext(request.page()), Instant.now()));
        return toResult(answer);
    }

    @Override
    public void close() {
        for (AgentSession session : sessions.values()) {
            try { session.close(); } catch (RuntimeException e) { log.warn("Error closing session: {}", e.getMessage()); }
        }
        sessions.clear();
        if (platform != null) platform.close();
    }

    private static AgentAskResult toResult(AgentAnswer answer) {
        List<AgentAskResult.Citation> citations = answer.citations() == null ? List.of()
                : answer.citations().stream().map(InspectoIntelligenceAgent::toCitation).toList();
        NavigationIntent nav = answer.navigation();
        return new AgentAskResult(answer.kind().name(), answer.text(), citations,
                nav == null ? null : nav.targetPageId());
    }

    private static AgentAskResult.Citation toCitation(Citation c) {
        return new AgentAskResult.Citation(c.sourceId(), c.locator());
    }

    @SuppressWarnings("unchecked")
    private static PageContext toPageContext(Map<String, Object> page) {
        if (page == null || page.isEmpty()) return null;
        String pageId = page.get("pageId") == null ? null : String.valueOf(page.get("pageId"));
        return new PageContext(pageId, stringMap(page.get("entityIds")), stringMap(page.get("filters")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return out;
    }
}
