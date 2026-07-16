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
import java.util.Set;

/**
 * Assembles the <em>structural</em> metadata graph from configuration alone (no audit, no DuckDB):
 * sources, schemas, columns and emitted event tables from the pipelines, Stage-2 transforms and
 * references from the enrichments, and the KPI/report semantic layer — wired together with the typed
 * edges ({@code DECLARES}, {@code DESCRIBES}, {@code EMITS}, {@code MATERIALIZES}, {@code FEEDS},
 * {@code JOINS_INTO}, {@code COMPUTED_FROM}, {@code CONSUMES}). Empty COLUMN descriptions are filled via
 * the {@link DescriptionProvider} SPI. Pure and stateless apart from its inputs — {@link MetadataGraphService}
 * caches the result and attaches the operational overlay separately.
 */
final class MetadataGraphBuilder {

    /** Order in which a bare ref (no {@code kind:} prefix) is resolved to a concrete node id. */
    private static final NodeKind[] RESOLUTION_ORDER = {
            NodeKind.DERIVED_TABLE, NodeKind.TABLE, NodeKind.KPI,
            NodeKind.REPORT, NodeKind.COLUMN, NodeKind.STREAM,
            NodeKind.RAW_SCHEMA, NodeKind.REFERENCE_DATASET
    };

    private final ConfigSource cs;
    private final List<DescriptionProvider> describers;

    MetadataGraphBuilder(ConfigSource cs, List<DescriptionProvider> describers) {
        this.cs = cs;
        this.describers = describers == null ? List.of() : describers;
    }

    MetadataGraph build() {
        Map<String, MetadataNode> nodes = new LinkedHashMap<>();
        List<MetadataEdge> edges = new ArrayList<>();
        Map<String, String> pipelineByDbRoot = new LinkedHashMap<>();

        // ── sources, schemas, columns, event tables ──────────────────────────────
        for (PipelineConfig cfg : cs.pipelines()) {
            String pipeline = cfg.identity().pipelineName();
            String dbRoot = cfg.dirs().database();
            String outFormat = cfg.output() == null ? null : cfg.output().format();
            if (dbRoot != null) pipelineByDbRoot.put(normPath(dbRoot), pipeline);

            // produces:reference ⇒ the origin registers as a standalone Reference Dataset (dimension
            // origin, id ref:<pipeline>) instead of a Stream; schemas/tables hang off it identically.
            boolean isReference = cfg.producesReference();
            String originId = isReference ? IdScheme.producedReference(pipeline) : IdScheme.stream(pipeline);
            Map<String, Object> srcAttrs = new LinkedHashMap<>();
            srcAttrs.put("pipeline", pipeline);
            if (cfg.dirs().poll() != null) srcAttrs.put("pollDir", cfg.dirs().poll());
            if (dbRoot != null) srcAttrs.put("database", dbRoot);
            if (isReference && outFormat != null && !outFormat.isBlank()) srcAttrs.put("format", outFormat);
            nodes.put(originId, new MetadataNode(
                    originId, isReference ? NodeKind.REFERENCE_DATASET : NodeKind.STREAM,
                    cfg.identity().name(), Description.EMPTY, srcAttrs));

            PipelineConfig.Schemas s = cfg.schemas();
            if (s.segments() != null && !s.segments().isEmpty()) {
                for (Map.Entry<String, Map<String, Object>> e : s.segments().entrySet()) {
                    addSchemaAndEvent(originId, pipeline, e.getKey(), e.getValue(), null, dbRoot, outFormat, nodes, edges);
                }
            } else if (s.selector() != null && s.selector().hasSchemas()) {
                int i = 0;
                for (SchemaSelector.Selection sel : s.selector().entries()) {
                    String key = firstNonBlank(sel.table(),
                            SchemaProjection.canonicalName(sel.schema()), "schema_" + i);
                    addSchemaAndEvent(originId, pipeline, key, sel.schema(), sel.table(), dbRoot, outFormat, nodes, edges);
                    i++;
                }
            } else if (s.single() != null) {
                String key = firstNonBlank(SchemaProjection.canonicalName(s.single()), "main");
                addSchemaAndEvent(originId, pipeline, key, s.single(), null, dbRoot, outFormat, nodes, edges);
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
            nodes.put(xid, new MetadataNode(xid, NodeKind.DERIVED_TABLE, en.name(),
                    Description.EMPTY, attrs));

            for (EnrichmentConfig.Reference r : en.references()) {
                String produced = r.byName() ? IdScheme.producedReference(r.ref()) : null;
                String rid;
                if (produced != null && nodes.containsKey(produced)) {
                    rid = produced;   // bind to the pipeline-produced Reference Dataset node
                } else {
                    rid = IdScheme.reference(en.name(), r.name());
                    Map<String, Object> rattrs = new LinkedHashMap<>();
                    if (r.byName()) {
                        rattrs.put("ref", r.ref());   // declared by name but that pipeline isn't loaded
                    } else {
                        rattrs.put("path", str(r.path()));
                        rattrs.put("format", str(r.format()));
                    }
                    nodes.put(rid, new MetadataNode(rid, NodeKind.REFERENCE_DATASET, r.name(),
                            Description.EMPTY, rattrs));
                }
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
                    if (tgt != null) edges.add(new MetadataEdge(rid, tgt, EdgeKind.CONSUMES));
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

    private void addSchemaAndEvent(String originId, String pipeline, String key, Map<String, Object> schema,
                                   String table, String dbRoot, String outputFormat,
                                   Map<String, MetadataNode> nodes, List<MetadataEdge> edges) {
        String sourceId = originId;
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
        nodes.put(eventId, new MetadataNode(eventId, NodeKind.TABLE, label,
                Description.EMPTY, evAttrs));
        edges.add(new MetadataEdge(sourceId, eventId, EdgeKind.EMITS));
        edges.add(new MetadataEdge(schemaId, eventId, EdgeKind.MATERIALIZES));
    }

    private static boolean linkSourceEvents(String pipeline, String xid,
                                            Map<String, MetadataNode> nodes, List<MetadataEdge> edges) {
        boolean any = false;
        for (MetadataNode n : nodes.values()) {
            if (n.kind() == NodeKind.TABLE && pipeline.equals(n.attrs().get("source"))) {
                edges.add(new MetadataEdge(n.id(), xid, EdgeKind.FEEDS));
                any = true;
            }
        }
        return any;
    }

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
