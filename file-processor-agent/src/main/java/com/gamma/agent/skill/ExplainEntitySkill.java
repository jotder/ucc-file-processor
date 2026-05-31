package com.gamma.agent.skill;

import com.gamma.agent.model.ModelProvider;
import com.gamma.agent.model.ModelRequest;
import com.gamma.agent.model.ModelTier;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.AssistResult.Citation;
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
 * synthesized answer with the exact grounding sources as {@link Citation}s.
 *
 * <h3>Why citations are derived, not parsed</h3>
 * The citations and links are built from the sources the skill actually fed the model, not parsed
 * from the model's free text. So even a small/local model can't fabricate a citation — every
 * reference points at a real catalog node id or doc file. The model supplies prose; the platform
 * supplies provenance.
 *
 * <h3>Safety</h3>
 * Zero write surface ({@code applyVia} is always {@code null}). When the model tier is unavailable
 * (e.g. no Ollama configured), it returns {@link AssistResult#unavailable} rather than throwing.
 */
public final class ExplainEntitySkill implements Skill {

    public static final String ID = "explain-entity";
    private static final int MAX_NEIGHBOURS = 8;
    private static final int MAX_DOC_SNIPPETS = 3;

    private static final String SYSTEM = """
            You are the UCC File Processor assist agent. Explain the entity to a data-platform
            operator using ONLY the CONTEXT provided below. Be concise, concrete, and accurate.
            Do not invent pipelines, tables, columns, metrics, or numbers that are not in the
            context. If the context is insufficient to answer, say so plainly.""";

    @Override public String id() { return ID; }
    @Override public ModelTier tier() { return ModelTier.MEDIUM; }

    @Override
    public AssistResult run(AssistRequest request, AssistContext ctx) {
        ModelProvider model = ctx.models().provider(tier());
        if (!model.available()) {
            return AssistResult.unavailable(ID,
                    "the assist model (tier " + tier() + ") is not available — enable the assist "
                            + "layer and a local Ollama endpoint to use explain-entity");
        }

        String question = request.userText() == null || request.userText().isBlank()
                ? "Explain this entity and its role in the pipeline."
                : request.userText();

        List<Citation> citations = new ArrayList<>();
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
            citations.add(new Citation("catalog", node.id()));
            links.add("/catalog/tables/" + node.id());

            appendNeighbours(context, citations, links, catalog, node.id());
        } else {
            headline = describeTarget(request);
            context.append("ENTITY: ").append(headline)
                    .append("\n(no matching catalog node was found; answer from docs/domain only)\n");
        }

        // ── Domain notes (currency/timezone/glossary) ──
        Map<String, Object> domain = catalog.domain();
        if (domain != null && !domain.isEmpty()) {
            context.append("DOMAIN NOTES: ").append(domain).append('\n');
            citations.add(new Citation("catalog", "domain"));
        }

        // ── Docs RAG (optional grounding) ──
        for (DocRetriever.Snippet s : ctx.docs().retrieve(question + " " + headline, MAX_DOC_SNIPPETS)) {
            context.append("DOC[").append(s.file()).append("]: ").append(s.text()).append('\n');
            citations.add(new Citation("doc", s.file()));
        }

        String prompt = "QUESTION: " + question + "\n\nCONTEXT:\n" + context;

        String answer;
        try {
            answer = model.generate(ModelRequest.text(tier(), SYSTEM, prompt));
        } catch (RuntimeException e) {
            return AssistResult.unavailable(ID, "the assist model call failed: " + e.getMessage());
        }

        return new AssistResult(ID, AssistResult.Status.OK, answer, citations, links,
                "synthesized from the metadata catalog" + (ctx.docs().isEmpty() ? "" : " + docs"),
                "local", false, null, null);
    }

    /** Resolve a catalog node id from the request: an explicit id, else entityType+id heuristics. */
    private static String resolveNodeId(AssistRequest req, MetadataGraphService catalog) {
        String explicit = firstNonBlank(req.context("nodeId"), req.context("id"));
        if (explicit != null) {
            // An already-qualified id (e.g. "event:foo/bar") — use as-is when it resolves.
            if (explicit.contains(":") && catalog.node(explicit) != null) return explicit;
            String entityType = req.context("entityType");
            if ("pipeline".equalsIgnoreCase(entityType) || "source".equalsIgnoreCase(entityType)) {
                String src = IdScheme.source(explicit);
                if (catalog.node(src) != null) return src;
            }
            if (catalog.node(explicit) != null) return explicit; // last-chance literal match
        }
        return null;
    }

    private static String describeTarget(AssistRequest req) {
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

    private static void appendNeighbours(StringBuilder ctx, List<Citation> citations, List<String> links,
                                         MetadataGraphService catalog, String id) {
        MetadataGraph graph = catalog.traverse(id, 2, MetadataGraphService.Direction.BOTH, null, null, false);
        int n = 0;
        StringBuilder line = new StringBuilder();
        for (MetadataNode nb : graph.nodes()) {
            if (nb.id().equals(id)) continue;
            if (n >= MAX_NEIGHBOURS) break;
            if (n > 0) line.append("; ");
            line.append(nb.label()).append(" (").append(nb.kind()).append(") [").append(nb.id()).append(']');
            citations.add(new Citation("catalog", nb.id()));
            links.add("/catalog/tables/" + nb.id());
            n++;
        }
        if (n > 0) ctx.append("RELATED: ").append(line).append('\n');
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }
}
