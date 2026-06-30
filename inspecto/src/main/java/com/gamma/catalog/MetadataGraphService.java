package com.gamma.catalog;

import com.gamma.catalog.spi.DescriptionProvider;

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
 * (no audit, no DuckDB) by {@link MetadataGraphBuilder}, cached, and rebuilt on {@link #invalidate()};
 * the <em>operational</em> overlay (status / completeness / error / lineage) is attached lazily, per
 * node, via an injected {@link OverlaySource} that reuses the existing audit reads.
 *
 * <p>The relationship spine the user cares about is explicit in the edges:
 * {@code SOURCE -EMITS-> TABLE}, {@code SOURCE -DECLARES-> RAW_SCHEMA -DESCRIBES-> COLUMN},
 * {@code RAW_SCHEMA -MATERIALIZES-> TABLE -FEEDS-> DERIVED_TABLE}, references
 * {@code -JOINS_INTO->} transforms, and {@code KPI -COMPUTED_FROM-> table/column},
 * {@code REPORT -CONSUMES-> kpi/table} — so a consumer can walk from a KPI all the way down to the
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
                    s = new MetadataGraphBuilder(cs, describers).build();
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
            if (n.kind() == NodeKind.TABLE || n.kind() == NodeKind.DERIVED_TABLE) out.add(n);
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

    // ── helpers ──────────────────────────────────────────────────────────────────

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
}
