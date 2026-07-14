package com.gamma.agent.skill;

import static com.gamma.agent.skill.SkillInputs.firstNonBlank;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.agent.CapabilitySpec;
import com.gamma.agent.kernel.error.AgentError;
import com.gamma.agent.kernel.agent.Capability;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.retrieve.ContextBudget;
import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;
import com.gamma.catalog.IdScheme;
import com.gamma.catalog.MetadataGraph;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.OperationalOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The first assist slice (v3.3.0, A1): read-only {@code explain-entity}. Given a catalog entity
 * (a pipeline/source/table/column/KPI) and an optional question, it grounds a local 7B model on
 * the M1 metadata graph — the node's description + attributes + operational overlay, its immediate
 * neighbours, the domain notes — plus any matching {@code docs/*.md} paragraphs, and returns a
 * synthesized answer with the exact grounding sources as {@link Evidence}.
 *
 * <h3>Why citations are derived, not parsed</h3>
 * The citations and links are built from the sources the skill actually fed the model, not parsed
 * from the model's free text. So even a small/local model can't fabricate a citation — every
 * reference points at a real catalog node id or doc file. The model supplies prose; the platform
 * supplies provenance.
 *
 * <h3>Safety</h3>
 * Zero write surface ({@code applyVia} is always {@code null}). When the model tier is unavailable
 * (e.g. no Ollama configured), it returns {@link AgentResult#unavailable} rather than throwing.
 */
public final class ExplainEntitySkill implements Capability {

    public static final String ID = "explain-entity";
    private static final int MAX_NEIGHBOURS = com.gamma.agent.model.AssistTunables.explainNeighbours(8);
    private static final int MAX_DOC_SNIPPETS = com.gamma.agent.model.AssistTunables.explainDocSnippets(3);

    private static final CapabilitySpec SPEC = new CapabilitySpec(ID, 1,
            "Explain a catalog entity grounded on the metadata graph and bundled docs.",
            ModelTier.MEDIUM, com.gamma.agent.model.AssistTunables.confidenceThreshold(0.5), java.time.Duration.ofSeconds(60),
            java.util.Set.of(), java.util.Set.of());

    private static final String SYSTEM = """
            You are Inspecto assist agent. Explain the entity to a data-platform
            operator using ONLY the CONTEXT provided below. Be concise, concrete, and accurate.
            Do not invent pipelines, tables, columns, metrics, or numbers that are not in the
            context. If the context is insufficient to answer, say so plainly.""";

    @Override public CapabilitySpec spec() { return SPEC; }

    @Override
    public AgentResult run(AgentRequest request, AgentContext agentContext) throws AgentError {
        UccAgentContext ctx = (UccAgentContext) agentContext;
        ModelTier tier = ctx.effectiveTier(SPEC.defaultTier());
        ModelProvider model = ctx.models().providerFor(tier);
        if (!model.available()) {
            return AgentResult.unavailable(ID,
                    "the assist model (tier " + tier + ") is not available — enable the assist "
                            + "layer and a local Ollama endpoint to use explain-entity");
        }

        String question = request.userText() == null || request.userText().isBlank()
                ? "Explain this entity and its role in the pipeline."
                : request.userText();

        List<Evidence> evidence = new ArrayList<>();
        List<String> links = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        // ── Ground on the catalog node, if we can resolve one ──
        MetadataGraphService catalog = ctx.catalog();
        String nodeId = resolveNodeId(request, catalog);
        MetadataNode node = (nodeId == null) ? null : catalog.hydrated(nodeId);
        String headline;
        if (node != null) {
            headline = node.label() + " (" + node.kind() + ") [" + node.id() + "]";
            context.append("ENTITY: ").append(headline).append('\n');
            if (node.description() != null && node.description().isPresent())
                context.append("DESCRIPTION: ").append(node.description().text()).append('\n');
            appendAttrs(context, node.attrs());
            appendOverlay(context, node.overlay());
            evidence.add(new Evidence(node.id(), CredibilityTier.AUTHORITATIVE, "catalog", node.id(), 1.0, null));
            links.add("/catalog/tables/" + node.id());

            appendNeighbours(context, evidence, links, catalog, node.id());
        } else {
            headline = describeTarget(request);
            context.append("ENTITY: ").append(headline)
                    .append("\n(no matching catalog node was found; answer from docs/domain only)\n");
        }

        // ── Domain notes (currency/timezone/glossary) ──
        Map<String, Object> domain = catalog.domain();
        if (domain != null && !domain.isEmpty()) {
            context.append("DOMAIN NOTES: ").append(domain).append('\n');
            evidence.add(new Evidence("domain", CredibilityTier.AUTHORITATIVE, "catalog", "domain", 1.0, null));
        }

        // ── Docs RAG (optional grounding). A retrieval failure must not sink the whole answer —
        // degrade to catalog-only grounding (B1 hardening, v4.1). ──
        try {
            for (Evidence ev : ctx.docs().retrieve(question + " " + headline,
                    new ContextBudget(0, MAX_DOC_SNIPPETS * 200, 0))) {
                context.append("DOC[").append(ev.sourceRef()).append("]: ").append(ev.value()).append('\n');
                evidence.add(new Evidence(ev.value(), CredibilityTier.INDICATIVE, "doc", ev.sourceRef(),
                        ev.confidence(), null));
            }
        } catch (RuntimeException e) {
            context.append("(doc retrieval unavailable: ").append(e.getMessage()).append(")\n");
        }

        String prompt = "QUESTION: " + question + "\n\nCONTEXT:\n" + context;

        String answer;
        try {
            answer = model.generate(ModelRequest.text(tier, SYSTEM, prompt)).text();
        } catch (RuntimeException e) {
            return AgentResult.unavailable(ID, "the assist model call failed: " + e.getMessage());
        }

        return new AgentResult(ID, SPEC.version(), AgentResult.Status.OK, answer, evidence, links,
                "synthesized from the metadata catalog" + (ctx.docs().isEmpty() ? "" : " + docs"),
                1.0, false, tier, null, null, null, java.util.Map.of());
    }

    /** Resolve a catalog node id from the request: an explicit id, else entityType+id heuristics. */
    private static String resolveNodeId(AgentRequest req, MetadataGraphService catalog) {
        String explicit = firstNonBlank(req.context("nodeId"), req.context("id"));
        if (explicit != null) {
            // An already-qualified id (e.g. "event:foo/bar") — use as-is when it resolves.
            if (explicit.contains(":") && catalog.node(explicit) != null) return explicit;
            String entityType = req.context("entityType");
            if ("pipeline".equalsIgnoreCase(entityType) || "source".equalsIgnoreCase(entityType)) {
                String src = IdScheme.stream(explicit);
                if (catalog.node(src) != null) return src;
            }
            if (catalog.node(explicit) != null) return explicit; // last-chance literal match
        }
        return null;
    }

    private static String describeTarget(AgentRequest req) {
        String type = req.context("entityType");
        String id = firstNonBlank(req.context("id"), req.context("nodeId"));
        if (type != null && id != null) return type + " '" + id + "'";
        return (id != null) ? id : "(unspecified entity)";
    }

    private static void appendAttrs(StringBuilder ctx, Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return;
        ctx.append("ATTRIBUTES: ").append(attrs).append('\n');
    }

    private static void appendOverlay(StringBuilder ctx, OperationalOverlay o) {
        if (o == null) return;
        ctx.append("OPERATIONAL: status=").append(o.latestStatus());
        if (o.latestRunTime() != null) ctx.append(", lastRun=").append(o.latestRunTime());
        ctx.append(", outputRows=").append(o.totalOutputRows())
                .append(", parsedRows=").append(o.parsedRows())
                .append(", errorRows=").append(o.errorRows());
        if (o.lastError() != null && !o.lastError().isBlank())
            ctx.append(", lastError=").append(o.lastError());
        ctx.append('\n');
    }

    private static void appendNeighbours(StringBuilder ctx, List<Evidence> evidence, List<String> links,
                                         MetadataGraphService catalog, String id) {
        MetadataGraph graph = catalog.traverse(id, 2, MetadataGraphService.Direction.BOTH, null, null, false);
        int n = 0;
        StringBuilder line = new StringBuilder();
        for (MetadataNode nb : graph.nodes()) {
            if (nb.id().equals(id)) continue;
            if (n >= MAX_NEIGHBOURS) break;
            if (n > 0) line.append("; ");
            line.append(nb.label()).append(" (").append(nb.kind()).append(") [").append(nb.id()).append(']');
            evidence.add(new Evidence(nb.id(), CredibilityTier.AUTHORITATIVE, "catalog", nb.id(), 1.0, null));
            links.add("/catalog/tables/" + nb.id());
            n++;
        }
        if (n > 0) ctx.append("RELATED: ").append(line).append('\n');
    }

}
