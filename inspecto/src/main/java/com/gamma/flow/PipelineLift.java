package com.gamma.flow;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifts a legacy {@link PipelineConfig} into the {@link FlowGraph} IR — an <b>internal</b>
 * representation only, never a file rewrite. The lift is faithful and <b>lossless</b>: typed
 * sub-records (the {@code source} sub-blocks, the {@link com.gamma.etl.CsvSettings} record, the
 * {@link SchemaSelector}, raw schema maps) are carried verbatim as node {@code config} values, so a
 * later compile-back (T5) can reproduce today's execution exactly. Phase 1 stops at the IR — nothing
 * here changes runtime behaviour.
 *
 * <h3>Shapes (doc §15 capability inventory)</h3>
 * <ul>
 *   <li><b>single schema</b> → linear {@code acq → [dedup] → parse → [filter] → map → sink}.</li>
 *   <li><b>selector</b> (multi-schema {@code schemas[]}) → one {@code parser} dispatcher emitting
 *       {@code route:<table>} branches to per-schema {@code map → sink}, plus {@code unmatched →
 *       quarantine}. The {@link SchemaSelector} (with its column-count / file_pattern priority) is
 *       carried on the parser node, so the route metadata (G3) is preserved.</li>
 *   <li><b>segments</b> (plugin ingester) → a plugin {@code parser} ({@code use: ingester/<fqcn>})
 *       emitting {@code route:<segment>} branches (G5).</li>
 * </ul>
 *
 * <p>Control wiring implied by existing flags: {@code gap_detection} → a {@code gap} node via a
 * {@code gap} edge (G7); {@code post_action} is carried on the {@code acquisition} node as a
 * success-side finalizer (G8), <em>not</em> a {@code failure} edge. The two dedup subsystems are kept
 * as <b>distinct</b> nodes (G2). Dead top-level keys ({@code version}/{@code search}/…) are dropped (F1).
 */
@PublicApi(since = "4.3.0")
public final class PipelineLift {

    private PipelineLift() {}

    // ── stable node ids ──────────────────────────────────────────────────────────
    static final String ACQ               = "acq";
    static final String DEDUP_MARKER      = "dedup_marker";
    static final String DEDUP_FINGERPRINT = "dedup_fingerprint";
    static final String PARSE             = "parse";
    static final String QUARANTINE        = "quarantine";
    static final String GAP               = "gap";

    /** Lift a loaded {@link PipelineConfig} into a {@link FlowGraph}. */
    public static FlowGraph lift(PipelineConfig cfg) {
        List<FlowNode> nodes = new ArrayList<>();
        List<FlowEdge> edges = new ArrayList<>();

        // 1. acquisition (entry) + optional gap control edge
        nodes.add(acquisitionNode(cfg));
        if (cfg.source().gapDetection().active()) {
            Map<String, Object> gap = new LinkedHashMap<>();
            put(gap, "sequence", cfg.source().gapDetection().sequence());
            nodes.add(new FlowNode(GAP, BuiltinNodeType.GAP.type(), "Gap detection", null, gap, null));
            edges.add(new FlowEdge(ACQ, FlowRel.GAP, GAP));
        }

        // 2. dedup prefix (two distinct subsystems — G2), feeding the parser
        String upstream = ACQ;
        if (cfg.processing().duplicateCheckEnabled()) {
            nodes.add(dedupMarkerNode(cfg));
            edges.add(FlowEdge.data(upstream, DEDUP_MARKER));
            upstream = DEDUP_MARKER;
        }
        if (cfg.source().duplicate().contentBased()) {
            nodes.add(dedupFingerprintNode(cfg));
            edges.add(FlowEdge.data(upstream, DEDUP_FINGERPRINT));
            upstream = DEDUP_FINGERPRINT;
        }

        // 3. parser, fed by the dedup prefix (or directly by acq)
        nodes.add(parserNode(cfg));
        edges.add(FlowEdge.data(upstream, PARSE));

        // 4. branch on schema resolution (exactly one of the three is set)
        PipelineConfig.Schemas s = cfg.schemas();
        Map<String, Object> sinkBase = sinkBaseConfig(cfg);
        boolean rowFilters = cfg.csv().hasRowFilters();

        if (s.selector() != null && s.selector().hasSchemas()) {
            int i = 0;
            for (SchemaSelector.Selection sel : s.selector().entries()) {
                String key = routeKey(sel.table(), i++);
                branch(nodes, edges, FlowRel.route(key), key, sel.schema(), sel.table(), sinkBase, cfg, rowFilters);
            }
            addQuarantine(nodes, edges, cfg);
        } else if (s.segments() != null && !s.segments().isEmpty()) {
            for (Map.Entry<String, Map<String, Object>> e : s.segments().entrySet()) {
                String key = routeKey(e.getKey(), 0);
                branch(nodes, edges, FlowRel.route(key), key, e.getValue(), e.getKey(), sinkBase, cfg, rowFilters);
            }
            addQuarantine(nodes, edges, cfg);
        } else {
            // single schema: one linear chain off the parser's data edge
            branch(nodes, edges, FlowRel.DATA, null, s.single(), null, sinkBase, cfg, rowFilters);
        }

        return new FlowGraph(cfg.identity().pipelineName(), cfg.active(), nodes, edges);
    }

    // ── node builders ──────────────────────────────────────────────────────────

    private static FlowNode acquisitionNode(PipelineConfig cfg) {
        PipelineConfig.Source src = cfg.source();
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "connector", src.connector());
        put(c, "poll", cfg.dirs().poll());
        put(c, "id", src.id());
        if (!src.includes().isEmpty()) c.put("includes", src.includes());
        if (!src.excludes().isEmpty()) c.put("excludes", src.excludes());
        c.put("recursive_depth", src.recursiveDepth());
        // typed sub-records — never null (Source canonical ctor defaults them), carried verbatim
        c.put("stability", src.stability());
        c.put("incremental", src.incremental());
        c.put("guarantee", src.guarantee());
        c.put("fetch", src.fetch());
        c.put("retry", src.retry());
        c.put("circuit_breaker", src.circuitBreaker());
        c.put("post_action", src.postAction());   // success-side finalizer (G8)
        if (cfg.triggerConfig() != null) c.put("trigger", cfg.triggerConfig());   // T13: entry-node trigger (§3.6)
        String use = src.hasConnection() ? "connection/" + src.connection() : null;
        return new FlowNode(ACQ, BuiltinNodeType.ACQUISITION.type(),
                "Acquisition", "Source: " + src.connector(), c, use);
    }

    private static FlowNode dedupMarkerNode(PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "marker_extension", cfg.processing().markerExtension());
        c.put("retention_days", cfg.processing().retentionDays());
        put(c, "markers_dir", cfg.dirs().markers());
        return new FlowNode(DEDUP_MARKER, BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type(),
                "Dedup (marker)", null, c, null);
    }

    private static FlowNode dedupFingerprintNode(PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("duplicate", cfg.source().duplicate());       // the fingerprint policy (mode/algorithm/on_change)
        c.put("incremental", cfg.source().incremental());   // watermark is derived alongside the ledger (G4)
        return new FlowNode(DEDUP_FINGERPRINT, BuiltinNodeType.TRANSFORM_DEDUP_FINGERPRINT.type(),
                "Dedup (fingerprint)", null, c, null);
    }

    private static FlowNode parserNode(PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("csv", cfg.csv());                 // the whole CsvSettings record (delimiter/skips/formats/…)
        c.put("chunking", cfg.chunking());
        if (cfg.fixedWidth() != null) c.put("fixedwidth", cfg.fixedWidth());
        PipelineConfig.Schemas s = cfg.schemas();
        if (s.selector() != null && s.selector().hasSchemas()) {
            c.put("selector", s.selector());     // carries column-count/file_pattern priority (G3)
        } else if (s.segments() != null && !s.segments().isEmpty()) {
            put(c, "ingester", s.ingesterClass());
            c.put("ingester_config", s.ingesterConfig());
            c.put("segments", s.segments());
        } else if (s.single() != null) {
            c.put("schema", s.single());         // raw.fields the parser tokenises against
        }
        String use = (s.ingesterClass() != null) ? "ingester/" + s.ingesterClass() : null;
        return new FlowNode(PARSE, BuiltinNodeType.PARSER.type(), "Parser", null, c, use);
    }

    /** Build one {@code [filter →] map → sink} chain off {@code parse} via {@code rel}; {@code key} is null for single-schema. */
    private static void branch(List<FlowNode> nodes, List<FlowEdge> edges, String rel, String key,
                               Map<String, Object> schema, String table,
                               Map<String, Object> sinkBase, PipelineConfig cfg, boolean rowFilters) {
        String suffix = (key == null) ? "" : "_" + key;
        String mapUpstream = PARSE;
        String mapUpstreamRel = rel;

        if (rowFilters) {   // index-anchored CSV row-filter sits between parser and map (G1)
            String filterId = "filter" + suffix;
            nodes.add(new FlowNode(filterId, BuiltinNodeType.TRANSFORM_FILTER.type(), "Row filter", null, filterConfig(cfg), null));
            edges.add(new FlowEdge(PARSE, rel, filterId));
            mapUpstream = filterId;
            mapUpstreamRel = FlowRel.DATA;
        }

        String mapId = "map" + suffix;
        Map<String, Object> mapCfg = new LinkedHashMap<>();
        if (schema != null) mapCfg.put("schema", schema);
        String mapName = (table != null && !table.isBlank()) ? "Map " + table : "Map";
        nodes.add(new FlowNode(mapId, BuiltinNodeType.TRANSFORM_MAP.type(), mapName, null, mapCfg, null));
        edges.add(new FlowEdge(mapUpstream, mapUpstreamRel, mapId));

        String sinkId = "sink" + suffix;
        Map<String, Object> sinkCfg = new LinkedHashMap<>(sinkBase);
        // The declared data-store this sink produces — the join key a downstream job/enrichment matches
        // its source store against, so the topology superimposes from config/metadata (see FlowStores).
        // Legacy pipelines only ever write a resting store, so the lift always emits sink.persistent;
        // sink.materialized / sink.view are authored-only (new capability, doc §3.1).
        String store = (table != null && !table.isBlank())
                ? table : canonicalName(schema, cfg.identity().pipelineName());
        put(sinkCfg, FlowStores.CONFIG_STORE, store);
        put(sinkCfg, "table", table);
        if (schema != null) sinkCfg.put("schema", schema);   // partitions derived from it at compile-back
        // The sink's display name is the store it produces — typically a business object/concept (§3.1).
        nodes.add(new FlowNode(sinkId, BuiltinNodeType.SINK_PERSISTENT.type(), store, "Persistent store", sinkCfg, null));
        edges.add(FlowEdge.data(mapId, sinkId));
    }

    private static void addQuarantine(List<FlowNode> nodes, List<FlowEdge> edges, PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "dir", cfg.dirs().quarantine());
        nodes.add(new FlowNode(QUARANTINE, BuiltinNodeType.SINK_PERSISTENT.type(), "Quarantine", "Unmatched files", c, null));
        edges.add(new FlowEdge(PARSE, FlowRel.UNMATCHED, QUARANTINE));
    }

    private static Map<String, Object> sinkBaseConfig(PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "format", cfg.output().format());
        put(c, "compression", cfg.output().compression());
        if (cfg.output().duckLake() != null) c.put("ducklake", cfg.output().duckLake());
        put(c, "database", cfg.dirs().database());
        put(c, "backup", cfg.dirs().backup());
        put(c, "temp", cfg.dirs().temp());
        c.put("batch_max_files", cfg.processing().batchMaxFiles());
        c.put("batch_max_bytes", cfg.processing().batchMaxBytes());
        c.put("threads", cfg.processing().threads());
        c.put("duckdb_threads", cfg.processing().duckdbThreads());
        c.put("duckdb", cfg.duckdb());
        return c;
    }

    private static Map<String, Object> filterConfig(PipelineConfig cfg) {
        var csv = cfg.csv();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("filter_target_column", csv.filterTargetColumn());
        if (!csv.includePrefixes().isEmpty()) c.put("include_prefixes", csv.includePrefixes());
        if (!csv.includeRegex().isEmpty())    c.put("include_regex", csv.includeRegex());
        if (!csv.excludePrefixes().isEmpty()) c.put("exclude_prefixes", csv.excludePrefixes());
        if (!csv.excludeRegex().isEmpty())    c.put("exclude_regex", csv.excludeRegex());
        return c;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The schema's {@code mapping.canonicalName} (the single-schema store name), or {@code fallback}. */
    private static String canonicalName(Map<String, Object> schema, String fallback) {
        if (schema != null && schema.get("mapping") instanceof Map<?, ?> mapping) {
            Object cn = mapping.get("canonicalName");
            if (cn != null && !cn.toString().isBlank()) return cn.toString();
        }
        return fallback;
    }

    /** Sanitise a table/segment name into a route-branch key; fall back to {@code schema_<i>}. */
    static String routeKey(String name, int i) {
        if (name == null || name.isBlank()) return "schema_" + i;
        String k = name.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return k.isEmpty() ? "schema_" + i : k;
    }

    /** Put a value only if non-null (config maps must not carry null values — {@link Map#copyOf}). */
    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }
}
