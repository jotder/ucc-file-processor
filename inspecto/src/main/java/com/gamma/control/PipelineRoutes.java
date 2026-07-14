package com.gamma.control;

import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineProjection;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.PipelineValidator;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.exec.PipelineDryRun;
import com.gamma.service.CollectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flow graph routes ({@code /pipelines*}): the read-only lifted-pipeline projections (T31) and the
 * authored-flow topology CRUD (T19, §7.1) persisted as {@code *_flow.toon} under the write root.
 * Extracted verbatim from {@link ControlApi}: identical routes, order, HTTP statuses and validation.
 */
final class PipelineRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(PipelineRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/pipelines", (e, m) -> flowSummaries(api));
        api.get("/pipelines/node-types", (e, m) -> PipelineProjection.catalog());
        api.get("/pipelines/combined", (e, m) -> combinedFlows(api));
        api.get("/pipelines/authored", (e, m) -> authoredFlowList(api));
        // Writes require canAuthorWorkbench (W6; a no-op on Personal — no Subject is ever attached there).
        api.post("/pipelines/authored", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createFlow(api, api.body(e))));
        api.get("/pipelines/authored/([^/]+)", (e, m) -> authoredFlow(api, ApiContext.name(m)));
        api.get("/pipelines/authored/([^/]+)/raw", (e, m) -> authoredFlowRaw(api, ApiContext.name(m)));
        api.put("/pipelines/authored/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> updateFlow(api, ApiContext.name(m), api.body(e))));
        api.delete("/pipelines/authored/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deleteFlow(api, ApiContext.name(m))));
        api.post("/pipelines/authored/([^/]+)/nodes", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> addFlowNode(api, ApiContext.name(m), api.body(e))));
        api.post("/pipelines/authored/([^/]+)/edges", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> addFlowEdge(api, ApiContext.name(m), api.body(e))));
        api.post("/pipelines/authored/([^/]+)/dry-run", (e, m) -> dryRunFlow(api, ApiContext.name(m), api.body(e)));
        api.get("/pipelines/([^/]+)/graph", (e, m) -> graphForPipeline(api, ApiContext.name(m)));
    }

    /** Lift every registered pipeline to a {@link PipelineGraph} and project a compact summary (GET /pipelines). */
    private Object flowSummaries(ApiContext api) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollectorService.PipelineView pv : api.service().pipelines()) {
            api.service().configFor(pv.name())
                    .ifPresent(c -> out.add(PipelineProjection.summary(PipelineLift.lift(c))));
        }
        return out;
    }

    /** Lift every registered pipeline and project the combined pipeline+job topology (GET /pipelines/combined, T24). */
    private Object combinedFlows(ApiContext api) {
        return PipelineProjection.combined(liftedFlows(api.service()));
    }

    /** Every registered pipeline lifted to a {@link PipelineGraph} (shared with the component safe-delete check). */
    static List<PipelineGraph> liftedFlows(CollectorService service) {
        List<PipelineGraph> graphs = new ArrayList<>();
        for (CollectorService.PipelineView pv : service.pipelines()) {
            service.configFor(pv.name()).ifPresent(c -> graphs.add(PipelineLift.lift(c)));
        }
        return graphs;
    }

    /** {@code GET /pipelines/{name}/graph} — lift one registered pipeline to its graph; 404 if no such pipeline. */
    private Object graphForPipeline(ApiContext api, String name) {
        PipelineConfig c = api.service().configFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        return PipelineProjection.graph(PipelineLift.lift(c));
    }

    private Path flowsRootOrNull(ApiContext api) {
        return api.writeRoot() == null ? null : api.writeRoot().resolve("flows");
    }

    private PipelineStore flowStore(ApiContext api) {
        return new PipelineStore(WriteGates.requireWriteRoot(api, "pipeline write").resolve("flows"));
    }

    /** {@code GET /pipelines/authored} — summaries of every authored flow (empty when no write root). */
    private Object authoredFlowList(ApiContext api) {
        Path root = flowsRootOrNull(api);
        if (root == null) return List.of();
        return new PipelineStore(root).list().stream().map(PipelineProjection::summary).toList();
    }

    /** {@code GET /pipelines/authored/{id}} — one authored flow's graph projection; 404 if absent. */
    private Object authoredFlow(ApiContext api, String id) {
        Path root = flowsRootOrNull(api);
        PipelineGraph g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        return PipelineProjection.graph(g);
    }

    /**
     * {@code GET /pipelines/authored/{id}/raw} — the <b>lossless</b> authored definition ({@link PipelineCodec#toMap},
     * nodes with their config) so the editor can round-trip a flow without dropping node config; the
     * {@link #authoredFlow} projection is structural-only. 404 if absent.
     */
    private Object authoredFlowRaw(ApiContext api, String id) {
        Path root = flowsRootOrNull(api);
        PipelineGraph g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        return PipelineCodec.toMap(g);
    }

    /** {@code POST /pipelines/authored} — create an authored flow from a posted flow definition; 409 if it exists. */
    private Object createFlow(ApiContext api, Map<String, Object> body) throws IOException {
        PipelineStore store = flowStore(api);
        PipelineGraph g = parseAndValidateFlow(body);
        String id = g.name();
        if (flowExists(store, id))
            throw new ApiException(409, "authored flow '" + id + "' already exists (use PUT to update)");
        return writeFlow(store, id, g);
    }

    /** {@code PUT /pipelines/authored/{id}} — create or replace an authored flow (URL id is authoritative). */
    private Object updateFlow(ApiContext api, String id, Map<String, Object> body) throws IOException {
        PipelineStore store = flowStore(api);
        Map<String, Object> withId = new LinkedHashMap<>(body);
        withId.put("name", id);   // the URL id wins over any name in the body
        return writeFlow(store, id, parseAndValidateFlow(withId));
    }

    /** {@code DELETE /pipelines/authored/{id}} — remove an authored flow; 404 if absent. */
    private Object deleteFlow(ApiContext api, String id) throws IOException {
        PipelineStore store = flowStore(api);
        if (!flowExists(store, id)) throw new ApiException(404, "no authored flow '" + id + "'");
        boolean removed;
        try {
            removed = store.delete(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return Map.of("id", id, "deleted", true, "fileRemoved", removed);
    }

    /** {@code POST /pipelines/authored/{id}/nodes} — add (or replace by id) a node, re-validate, persist. */
    private Object addFlowNode(ApiContext api, String id, Map<String, Object> body) throws IOException {
        PipelineStore store = flowStore(api);
        PipelineGraph g = requireAuthoredFlow(store, id);
        PipelineNode node;
        try {
            node = PipelineCodec.nodeFromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
        List<PipelineNode> nodes = new ArrayList<>(g.nodes());
        nodes.removeIf(n -> n.id().equals(node.id()));   // upsert by node id
        nodes.add(node);
        PipelineGraph updated = new PipelineGraph(g.name(), g.active(), nodes, g.edges());
        validateFlow(updated);
        return writeFlow(store, id, updated);
    }

    /** {@code POST /pipelines/authored/{id}/edges} — add an edge, re-validate, persist. */
    private Object addFlowEdge(ApiContext api, String id, Map<String, Object> body) throws IOException {
        PipelineStore store = flowStore(api);
        PipelineGraph g = requireAuthoredFlow(store, id);
        PipelineEdge edge;
        try {
            edge = PipelineCodec.edgeFromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
        List<PipelineEdge> edges = new ArrayList<>(g.edges());
        edges.add(edge);
        PipelineGraph updated = new PipelineGraph(g.name(), g.active(), g.nodes(), edges);
        validateFlow(updated);
        return writeFlow(store, id, updated);
    }

    private PipelineGraph requireAuthoredFlow(PipelineStore store, String id) {
        try {
            return store.get(id).orElseThrow(() -> new ApiException(404, "no authored flow '" + id + "'"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** Parse a flow definition (400 on a malformed shape) and validate it (422 on validation errors). */
    private PipelineGraph parseAndValidateFlow(Map<String, Object> body) {
        PipelineGraph g;
        try {
            g = PipelineCodec.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        validateFlow(g);
        return g;
    }

    private void validateFlow(PipelineGraph g) {
        PipelineValidator.Result r = PipelineValidator.validate(g);
        if (!r.ok())
            throw new ApiException(422, "flow validation failed: " + r.errors().stream()
                    .map(i -> i.code() + " — " + i.message()).toList());
    }

    private static boolean flowExists(PipelineStore store, String id) {
        try {
            return store.exists(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private Object writeFlow(PipelineStore store, String id, PipelineGraph g) throws IOException {
        try {
            store.write(id, g);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
        log.info("[PIPELINE-WRITE] wrote authored flow {}", id);
        return PipelineProjection.graph(g);
    }

    /**
     * {@code POST /pipelines/authored/{id}/dry-run} — run a bounded sample through an authored flow's
     * transform→sink subgraph on a throwaway DuckDB (T18, §7.2); per-node + per-sink row counts. 404 if the
     * flow is absent, 400 on a bad sample, 422 on a validation/SQL error. Never touches production output.
     */
    private Object dryRunFlow(ApiContext api, String id, Map<String, Object> body) {
        Path root = flowsRootOrNull(api);
        PipelineGraph g;
        try {
            g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        try {
            return PipelineDryRun.run(g, ApiContext.sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (Exception e) {
            throw new ApiException(422, "dry-run failed: " + e.getMessage());
        }
    }
}
