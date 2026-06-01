package com.gamma.catalog;

import com.gamma.catalog.spi.DescriptionProvider;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Assembles and serves the metadata graph: a connected, typed projection of the platform's
 * sources, schemas, columns, emitted event tables, Stage-2 transforms, references, and the
 * KPI/report semantic layer. The <em>structural</em> graph is derived purely from configuration
 * (no audit, no DuckDB), cached, and rebuilt on {@link #invalidate()}; the <em>operational</em>
 * overlay (status / completeness / error / lineage) is attached lazily, per node, via an
 * injected {@link OverlaySource} that reuses the existing audit reads.
 *
 * <p>The relationship spine the user cares about is explicit in the edges:
 * {@code SOURCE -EMITS-> EVENT_TABLE}, {@code SOURCE -DECLARES-> RAW_SCHEMA -DESCRIBES-> COLUMN},
 * {@code RAW_SCHEMA -MATERIALIZES-> EVENT_TABLE -FEEDS-> TRANSFORMED_TABLE}, references
 * {@code -JOINS_INTO->} transforms, and {@code KPI -COMPUTED_FROM-> table/column},
 * {@code REPORT -USES-> kpi/table} — so a consumer can walk from a KPI all the way down to the
 * source columns that feed it.
 */
public final class MetadataGraphService {

    /** BFS direction over directed edges. */
    public enum Direction { OUT, IN, BOTH }

    /** Supplies the lazily-fetched operational overlay for a node (implemented in P4). */
    @FunctionalInterface
    public interface OverlaySource {
        OperationalOverlay overlayFor(MetadataNode node);
    }

    /** Order in which a bare ref (no {@code kind:} prefix) is resolved to a concrete node id. */
    private static final NodeKind[] RESOLUTION_ORDER = {
            NodeKind.TRANSFORMED_TABLE, NodeKind.EVENT_TABLE, NodeKind.KPI,
            NodeKind.REPORT, NodeKind.COLUMN, NodeKind.SOURCE,
            NodeKind.RAW_SCHEMA, NodeKind.REFERENCE_TABLE
    };

    private final ConfigSource cs;
    private final OverlaySource overlay;   // nullable; null -> overlays report NONE
    private final List<DescriptionProvider> describers;
    private volatile MetadataGraph structural;

    public MetadataGraphService(ConfigSource cs) {
        this(cs, null);
    }

    public MetadataGraphService(ConfigSource cs, OverlaySource overlay) {
        this(cs, overlay, discoverDescribers());
    }

    /** Explicit-describers constructor (tests / embedders supplying providers directly). */
    public MetadataGraphService(ConfigSource cs, OverlaySource overlay, List<DescriptionProvider> describers) {
        this.cs = cs;
        this.overlay = overlay;
        this.describers = describers == null ? List.of() : List.copyOf(describers);
    }

    /** Discover description providers on the classpath (the agent module adds an AI one at M3). */
    private static List<DescriptionProvider> discoverDescribers() {
        List<DescriptionProvider> out = new ArrayList<>();
        for (DescriptionProvider p : ServiceLoader.load(DescriptionProvider.class)) out.add(p);
        return out;
    }

    // ── cache ────────────────────────────────────────────────────────────────────

    /** The cached structural graph, built on first access. */
    public MetadataGraph structural() {
        MetadataGraph s = structural;
        if (s == null) {
            synchronized (this) {
                s = structural;
                if (s == null) {
                    s = rebuildStructural();
                    structural = s;
                }
            }
        }
        return s;
    }

    /** Drop the cached structural graph; the next access rebuilds it (call on config reload). */
    public void invalidate() {
        structural = null;
    }

    // ── queries ──────────────────────────────────────────────────────────────────

    /** A structural node by id (no overlay), or {@code null}. */
    public MetadataNode node(String id) {
        for (MetadataNode n : structural().nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }

    /** A node with its operational overlay hydrated, or {@code null} if unknown. */
    public MetadataNode hydrated(String id) {
        MetadataNode n = node(id);
        return n == null ? null : hydrate(n);
    }

    /** Every table node (Stage-1 event tables + Stage-2 transformed tables), no overlay. */
    public List<MetadataNode> tables() {
        List<MetadataNode> out = new ArrayList<>();
        for (MetadataNode n : structural().nodes()) {
            if (n.kind() == NodeKind.EVENT_TABLE || n.kind() == NodeKind.TRANSFORMED_TABLE) out.add(n);
        }
        return out;
    }

    /** All nodes of a given kind (no overlay). */
    public List<MetadataNode> nodesOfKind(NodeKind kind) {
        List<MetadataNode> out = new ArrayList<>();
        for (MetadataNode n : structural().nodes()) {
            if (n.kind() == kind) out.add(n);
        }
        return out;
    }

    /** Merged domain notes (currency / timezone / notes) across all semantic models. */
    public Map<String, Object> domain() {
        String currency = "";
        String timezone = "";
        List<String> notes = new ArrayList<>();
        for (SemanticModel m : cs.semantics()) {
            if (!m.domain().currency().isBlank()) currency = m.domain().currency();
            if (!m.domain().timezone().isBlank()) timezone = m.domain().timezone();
            notes.addAll(m.domain().notes());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", currency);
        out.put("timezone", timezone);
        out.put("notes", notes);
        return out;
    }

    /**
     * A filterable subgraph. With a blank {@code from} the whole graph is returned (filtered by
     * {@code kinds}/{@code edgeKinds}); otherwise a BFS from {@code from} up to {@code depth} hops,
     * traversing only {@code edgeKinds} in {@code dir}. {@code kinds} restricts the returned node
     * set; {@code overlay} hydrates the returned nodes.
     */
    public MetadataGraph traverse(String from, int depth, Direction dir,
                                  Set<NodeKind> kinds, Set<EdgeKind> edgeKinds, boolean overlay) {
        MetadataGraph g = structural();
        List<MetadataEdge> activeEdges = new ArrayList<>();
        for (MetadataEdge e : g.edges()) {
            if (edgeKinds == null || edgeKinds.isEmpty() || edgeKinds.contains(e.kind())) activeEdges.add(e);
        }

        Map<String, MetadataNode> byId = index(g);
        Set<String> reached;
        if (from == null || from.isBlank()) {
            reached = new LinkedHashSet<>(byId.keySet());
        } else if (!byId.containsKey(from)) {
            return MetadataGraph.EMPTY;
        } else {
            reached = bfs(from, Math.max(0, depth), dir == null ? Direction.BOTH : dir, activeEdges);
        }

        List<MetadataNode> outNodes = new ArrayList<>();
        Set<String> keptIds = new LinkedHashSet<>();
        for (String id : reached) {
            MetadataNode n = byId.get(id);
            if (n == null) continue;
            if (kinds != null && !kinds.isEmpty() && !kinds.contains(n.kind())) continue;
            outNodes.add(overlay ? hydrate(n) : n);
            keptIds.add(id);
        }
        List<MetadataEdge> outEdges = new ArrayList<>();
        for (MetadataEdge e : activeEdges) {
            if (keptIds.contains(e.from()) && keptIds.contains(e.to())) outEdges.add(e);
        }
        return new MetadataGraph(outNodes, outEdges);
    }

    // ── overlay ──────────────────────────────────────────────────────────────────

    /** Attach the operational overlay to a node (NONE when no overlay source is wired). */
    public MetadataNode hydrate(MetadataNode n) {
        if (overlay == null) return n.withOverlay(OperationalOverlay.NONE);
        OperationalOverlay o;
        try {
            o = overlay.overlayFor(n);
        } catch (Exception e) {
            o = OperationalOverlay.NONE;
        }
        return n.withOverlay(o == null ? OperationalOverlay.NONE : o);
    }

    // ── structural assembly ────────────────────────────────────────────────────────

    private MetadataGraph rebuildStructural() {
        Map<String, MetadataNode> nodes = new LinkedHashMap<>();
        List<MetadataEdge> edges = new ArrayList<>();
        Map<String, String> pipelineByDbRoot = new LinkedHashMap<>();

        // ── sources, schemas, columns, event tables ──────────────────────────────
        for (PipelineConfig cfg : cs.pipelines()) {
            String pipeline = cfg.identity().pipelineName();
            String dbRoot = cfg.dirs().database();
            String outFormat = cfg.output() == null ? null : cfg.output().format();
            if (dbRoot != null) pipelineByDbRoot.put(normPath(dbRoot), pipeline);

            Map<String, Object> srcAttrs = new LinkedHashMap<>();
            srcAttrs.put("pipeline", pipeline);
            if (cfg.dirs().poll() != null) srcAttrs.put("pollDir", cfg.dirs().poll());
            if (dbRoot != null) srcAttrs.put("database", dbRoot);
            nodes.put(IdScheme.source(pipeline), new MetadataNode(
                    IdScheme.source(pipeline), NodeKind.SOURCE, cfg.identity().name(),
                    Description.EMPTY, srcAttrs));

            PipelineConfig.Schemas s = cfg.schemas();
            if (s.segments() != null && !s.segments().isEmpty()) {
                for (Map.Entry<String, Map<String, Object>> e : s.segments().entrySet()) {
                    addSchemaAndEvent(pipeline, e.getKey(), e.getValue(), null, dbRoot, outFormat, nodes, edges);
                }
            } else if (s.selector() != null && s.selector().hasSchemas()) {
                int i = 0;
                for (SchemaSelector.Selection sel : s.selector().entries()) {
                    String key = firstNonBlank(sel.table(),
                            SchemaProjection.canonicalName(sel.schema()), "schema_" + i);
                    addSchemaAndEvent(pipeline, key, sel.schema(), sel.table(), dbRoot, outFormat, nodes, edges);
                    i++;
                }
            } else if (s.single() != null) {
                String key = firstNonBlank(SchemaProjection.canonicalName(s.single()), "main");
                addSchemaAndEvent(pipeline, key, s.single(), null, dbRoot, outFormat, nodes, edges);
            }
        }

        // ── transformed tables + references (nodes) ───────────────────────────────
        for (EnrichmentConfig en : cs.enrichments()) {
            String xid = IdScheme.xform(en.name());
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("stage", 2);
            if (en.output() != null) {
                attrs.put("outputDb", en.output().database());
                attrs.put("grain", en.output().partitions());
                attrs.put("format", en.output().format());
            }
            nodes.put(xid, new MetadataNode(xid, NodeKind.TRANSFORMED_TABLE, en.name(),
                    Description.EMPTY, attrs));

            for (EnrichmentConfig.Reference r : en.references()) {
                String rid = IdScheme.reference(en.name(), r.name());
                Map<String, Object> rattrs = new LinkedHashMap<>();
                rattrs.put("path", str(r.path()));
                rattrs.put("format", str(r.format()));
                nodes.put(rid, new MetadataNode(rid, NodeKind.REFERENCE_TABLE, r.name(),
                        Description.EMPTY, rattrs));
                edges.add(new MetadataEdge(rid, xid, EdgeKind.JOINS_INTO));
            }
        }

        // ── FEEDS lineage (edges; needs all nodes present) ────────────────────────
        for (EnrichmentConfig en : cs.enrichments()) {
            String xid = IdScheme.xform(en.name());
            boolean linked = false;
            String up = en.triggers() == null ? null : en.triggers().onPipeline();
            if (up != null && !up.isBlank()) {
                String upXform = IdScheme.xform(up.trim());
                if (nodes.containsKey(upXform)) {
                    edges.add(new MetadataEdge(upXform, xid, EdgeKind.FEEDS));
                    linked = true;
                } else {
                    linked = linkSourceEvents(up.trim().toLowerCase().replace(' ', '_'), xid, nodes, edges);
                }
            }
            if (!linked && en.input() != null) {
                String up2 = pipelineByDbRoot.get(normPath(en.input().database()));
                if (up2 != null) linkSourceEvents(up2, xid, nodes, edges);
            }
        }

        // ── semantic layer: KPI / report nodes, table descriptions ────────────────
        for (SemanticModel m : cs.semantics()) {
            for (SemanticModel.TableMeta t : m.tables().values()) {
                String id = resolve(t.ref(), nodes.keySet());
                if (id == null || !nodes.containsKey(id)) continue;
                MetadataNode n = nodes.get(id);
                Description d = t.description().isBlank()
                        ? n.description()
                        : n.description().mergePreferring(Description.manual(t.description()));
                Map<String, Object> attrs = new LinkedHashMap<>(n.attrs());
                if (!t.grain().isBlank()) attrs.put("grainNote", t.grain());
                nodes.put(id, new MetadataNode(n.id(), n.kind(), n.label(), d, attrs, n.overlay()));
            }
            for (SemanticModel.KpiMeta k : m.kpis().values()) {
                String kid = IdScheme.kpi(k.name());
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("definition", k.definition());
                if (!k.grain().isBlank()) attrs.put("grain", k.grain());
                if (!k.joinKeys().isEmpty()) attrs.put("joinKeys", k.joinKeys());
                nodes.put(kid, new MetadataNode(kid, NodeKind.KPI, k.name(),
                        k.definition().isBlank() ? Description.EMPTY : Description.manual(k.definition()),
                        attrs));
            }
            for (SemanticModel.ReportMeta r : m.reports().values()) {
                String rid = IdScheme.report(r.name());
                nodes.put(rid, new MetadataNode(rid, NodeKind.REPORT, r.name(),
                        r.description().isBlank() ? Description.EMPTY : Description.manual(r.description()),
                        Map.of()));
            }
        }

        // ── semantic layer: COMPUTED_FROM / USES (edges; needs KPI+report nodes) ──
        for (SemanticModel m : cs.semantics()) {
            for (SemanticModel.KpiMeta k : m.kpis().values()) {
                String kid = IdScheme.kpi(k.name());
                for (String in : k.inputs()) {
                    String tgt = resolve(in, nodes.keySet());
                    if (tgt != null) edges.add(new MetadataEdge(kid, tgt, EdgeKind.COMPUTED_FROM));
                }
            }
            for (SemanticModel.ReportMeta r : m.reports().values()) {
                String rid = IdScheme.report(r.name());
                for (String use : r.uses()) {
                    String tgt = resolve(use, nodes.keySet());
                    if (tgt != null) edges.add(new MetadataEdge(rid, tgt, EdgeKind.USES));
                }
            }
        }

        // fill empty column descriptions via the provider SPI (no-op in core; AI at M3)
        applyDescribers(nodes);

        // de-dup edges, preserving order
        List<MetadataEdge> deduped = new ArrayList<>(new LinkedHashSet<>(edges));
        return new MetadataGraph(new ArrayList<>(nodes.values()), deduped);
    }

    /**
     * Ask the description providers to fill any COLUMN whose description is {@link Provenance#NONE}.
     * Authored ({@code MANUAL}) and already-suggested prose is never downgraded — results are merged
     * with {@link Description#mergePreferring}. A provider that throws is treated as an abstention.
     */
    private void applyDescribers(Map<String, MetadataNode> nodes) {
        if (describers.isEmpty()) return;
        for (Map.Entry<String, MetadataNode> entry : nodes.entrySet()) {
            MetadataNode n = entry.getValue();
            if (n.kind() != NodeKind.COLUMN || n.description().provenance() != Provenance.NONE) continue;

            String rest = n.id().substring(n.id().indexOf(':') + 1);
            String[] parts = rest.split("/", 3);
            String pipeline = parts.length > 0 ? parts[0] : "";
            String table = parts.length > 1 ? parts[1] : "";
            DescriptionProvider.ColumnContext ctx = new DescriptionProvider.ColumnContext(
                    pipeline, table, n.label(), str(n.attrs().get("type")), n.description().text());

            Description d = n.description();
            for (DescriptionProvider dp : describers) {
                Description suggestion;
                try {
                    suggestion = dp.describeColumn(ctx);
                } catch (Exception e) {
                    suggestion = Description.EMPTY;
                }
                if (suggestion != null) d = d.mergePreferring(suggestion);
            }
            if (d != n.description()) entry.setValue(n.withDescription(d));
        }
    }

    private void addSchemaAndEvent(String pipeline, String key, Map<String, Object> schema,
                                   String table, String dbRoot, String outputFormat,
                                   Map<String, MetadataNode> nodes, List<MetadataEdge> edges) {
        String sourceId = IdScheme.source(pipeline);
        String schemaId = IdScheme.schema(pipeline, key);
        String eventId = IdScheme.event(pipeline, key);

        Map<String, Object> schemaAttrs = new LinkedHashMap<>();
        schemaAttrs.put("source", pipeline);
        schemaAttrs.put("format", rawFormat(schema));
        nodes.put(schemaId, new MetadataNode(schemaId, NodeKind.RAW_SCHEMA, key,
                Description.EMPTY, schemaAttrs));
        edges.add(new MetadataEdge(sourceId, schemaId, EdgeKind.DECLARES));

        for (SchemaProjection.Column c : SchemaProjection.columns(schema)) {
            String colId = IdScheme.column(pipeline, key, c.name());
            Map<String, Object> cattrs = new LinkedHashMap<>();
            cattrs.put("type", c.type());
            if (!c.unit().isBlank()) cattrs.put("unit", c.unit());
            if (!c.classification().isBlank()) cattrs.put("classification", c.classification());
            nodes.put(colId, new MetadataNode(colId, NodeKind.COLUMN, c.name(), c.description(), cattrs));
            edges.add(new MetadataEdge(schemaId, colId, EdgeKind.DESCRIBES));
        }

        Map<String, Object> evAttrs = new LinkedHashMap<>();
        evAttrs.put("stage", 1);
        evAttrs.put("source", pipeline);
        evAttrs.put("eventType", key);
        evAttrs.put("grain", grainOf(schema));
        if (table != null && !table.isBlank()) evAttrs.put("table", table);
        if (dbRoot != null) evAttrs.put("outputGlob", dbRoot);
        if (outputFormat != null && !outputFormat.isBlank()) evAttrs.put("format", outputFormat);
        String label = (table != null && !table.isBlank()) ? table : key;
        nodes.put(eventId, new MetadataNode(eventId, NodeKind.EVENT_TABLE, label,
                Description.EMPTY, evAttrs));
        edges.add(new MetadataEdge(sourceId, eventId, EdgeKind.EMITS));
        edges.add(new MetadataEdge(schemaId, eventId, EdgeKind.MATERIALIZES));
    }

    private static boolean linkSourceEvents(String pipeline, String xid,
                                            Map<String, MetadataNode> nodes, List<MetadataEdge> edges) {
        boolean any = false;
        for (MetadataNode n : nodes.values()) {
            if (n.kind() == NodeKind.EVENT_TABLE && pipeline.equals(n.attrs().get("source"))) {
                edges.add(new MetadataEdge(n.id(), xid, EdgeKind.FEEDS));
                any = true;
            }
        }
        return any;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String resolve(String ref, Set<String> ids) {
        if (ref == null || ref.isBlank()) return null;
        String r = ref.trim();
        if (ids.contains(r)) return r;
        for (NodeKind k : RESOLUTION_ORDER) {
            String cand = IdScheme.token(k) + ":" + r;
            if (ids.contains(cand)) return cand;
        }
        return null;
    }

    private static Map<String, MetadataNode> index(MetadataGraph g) {
        Map<String, MetadataNode> byId = new LinkedHashMap<>();
        for (MetadataNode n : g.nodes()) byId.put(n.id(), n);
        return byId;
    }

    private static Set<String> bfs(String from, int depth, Direction dir, List<MetadataEdge> edges) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(from);
        Set<String> frontier = new LinkedHashSet<>();
        frontier.add(from);
        for (int d = 0; d < depth; d++) {
            Set<String> next = new LinkedHashSet<>();
            for (MetadataEdge e : edges) {
                if ((dir == Direction.OUT || dir == Direction.BOTH)
                        && frontier.contains(e.from()) && !visited.contains(e.to())) next.add(e.to());
                if ((dir == Direction.IN || dir == Direction.BOTH)
                        && frontier.contains(e.to()) && !visited.contains(e.from())) next.add(e.from());
            }
            if (next.isEmpty()) break;
            visited.addAll(next);
            frontier = next;
        }
        return visited;
    }

    private static List<String> grainOf(Map<String, Object> schema) {
        List<String> out = new ArrayList<>();
        if (schema == null) return out;
        if (schema.get("partitions") instanceof List<?> parts) {
            for (Object p : parts) {
                if (p instanceof Map<?, ?> pm && pm.get("column") != null) out.add(pm.get("column").toString());
            }
        }
        if (out.isEmpty() && schema.get("partitionKey") instanceof String pk && !pk.isBlank()) out.add(pk);
        return out;
    }

    private static String rawFormat(Map<String, Object> schema) {
        if (schema != null && schema.get("raw") instanceof Map<?, ?> raw && raw.get("format") != null) {
            return raw.get("format").toString();
        }
        return "";
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String normPath(String p) {
        if (p == null) return "";
        String s = p.replace('\\', '/').trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
