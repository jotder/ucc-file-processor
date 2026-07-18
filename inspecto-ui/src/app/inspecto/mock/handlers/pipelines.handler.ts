import type {
    AuthoredNode,
    AuthoredPipeline,
    PipelineDryRunResult,
    PipelineGraph,
    PipelineNode,
    PipelineNodeType,
    PipelineRunRelation,
    PipelineRunResult,
    PipelineSummary,
} from '../../api/pipelines.service';
import type { PipelineViewData, PipelineViewSummary } from '../../api/views.service';
import type { IconMap } from '../../api/icon-map.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest, MockResponse } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The Pipelines-editor mock domain (authored-DAG CRUD, graph projections, dry-run, run-to-here,
 * icon map, provenance stubs) — the `/pipelines` half of the old `pipeline-mock` interceptor, now
 * backed by the persistent {@link MockStore} (`authored-pipeline` collection + the `config/icon-map`
 * singleton) so authored pipelines survive a reload. Behavior is otherwise a faithful port.
 */

/** The processor palette — grouped by category (Collector=SOURCE, Parser=PARSE, Transformer=TRANSFORM, Writer=SINK). */
export const NODE_TYPES: PipelineNodeType[] = [
    // Collector (SOURCE)
    { type: 'collector.file', category: 'SOURCE', label: 'File', description: 'Collect files from a directory or remote connection.', accepts: [], emits: ['success', 'failure'], emitsNamedRoutes: false },
    { type: 'collector.database', category: 'SOURCE', label: 'Database extract', description: 'Extract rows from a table/query (incremental watermark).', accepts: [], emits: ['success', 'failure'], emitsNamedRoutes: false },
    { type: 'collector.stream', category: 'SOURCE', label: 'Streaming feed', description: 'Micro-batch from a stream (Mongo / Redis / Kafka).', accepts: [], emits: ['success', 'failure'], emitsNamedRoutes: false },
    // Parser (PARSE)
    { type: 'parser.dsv', category: 'PARSE', label: 'DSV (delimited)', description: 'Parse delimited text (CSV / TSV / pipe).', accepts: ['data'], emits: ['success', 'unmatched'], emitsNamedRoutes: false },
    { type: 'parser.asn1', category: 'PARSE', label: 'ASN.1', description: 'Decode ASN.1-encoded records.', accepts: ['data'], emits: ['success', 'unmatched'], emitsNamedRoutes: false },
    { type: 'parser.text', category: 'PARSE', label: 'Text', description: 'Parse free-form or fixed-width text.', accepts: ['data'], emits: ['success', 'unmatched'], emitsNamedRoutes: false },
    { type: 'parser.other', category: 'PARSE', label: 'Other', description: 'Custom / plugin record parser.', accepts: ['data'], emits: ['success', 'unmatched'], emitsNamedRoutes: false },
    // Transformer (TRANSFORM)
    { type: 'transform.record', category: 'TRANSFORM', label: 'Record transform', description: 'Derive, rename, cast or drop fields.', accepts: ['data'], emits: ['success', 'failure'], emitsNamedRoutes: false },
    { type: 'transform.route', category: 'TRANSFORM', label: 'Route', description: 'Route records to named branches by content.', accepts: ['data'], emits: ['unmatched'], emitsNamedRoutes: true },
    { type: 'transform.filter', category: 'TRANSFORM', label: 'Filter', description: 'Keep or drop records by predicate.', accepts: ['data'], emits: ['kept', 'dropped'], emitsNamedRoutes: false },
    { type: 'transform.aggregate', category: 'TRANSFORM', label: 'Aggregation', description: 'Group and aggregate (sum / count / …).', accepts: ['data'], emits: ['success'], emitsNamedRoutes: false },
    { type: 'transform.alert', category: 'TRANSFORM', label: 'Alert', description: 'Raise an alert when a condition matches.', accepts: ['data'], emits: ['success'], emitsNamedRoutes: false },
    // Writer (SINK) — added so a pipeline has an output; rename/extend as the taxonomy grows.
    { type: 'sink.file', category: 'SINK', label: 'File writer', description: 'Write records to files (CSV / Parquet).', accepts: ['data'], emits: [], emitsNamedRoutes: false },
    { type: 'sink.database', category: 'SINK', label: 'Database load', description: 'Load records into a database table.', accepts: ['data'], emits: [], emitsNamedRoutes: false },
];

const CATEGORY_OF = new Map(NODE_TYPES.map((t) => [t.type, t.category]));

/** MockStore collection for authored pipelines (distinct from the `component:*` kind collections). */
export const PIPELINES_COLL = 'authored-pipeline';

const FLOWS = /\/pipelines$/;
const NODE_TYPES_RE = /\/pipelines\/node-types$/;
const COMBINED = /\/pipelines\/combined$/;
const AUTHORED = /\/pipelines\/authored$/;
const AUTHORED_RAW = /\/pipelines\/authored\/([^/]+)\/raw$/;
const DRY_RUN = /\/pipelines\/authored\/([^/]+)\/dry-run$/;
const RUN_TO = /\/pipelines\/authored\/([^/]+)\/run$/;
const AUTHORED_ID = /\/pipelines\/authored\/([^/]+)$/;
const FLOW_GRAPH = /\/pipelines\/([^/]+)\/graph$/;
const PROV_BATCHES = /\/provenance\/batches$/;
const PROV = /\/provenance$/;
const ICON_MAP_RE = /\/config\/icon-map$/;
const VIEWS = /\/views$/;
const VIEW_DATA = /\/views\/([^/]+)\/data$/;
const VIEW_NAME = /\/views\/([^/]+)$/;

export function pipelinesHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockFlows) return undefined;
        const { method, url, space } = req;
        const all = (): AuthoredPipeline[] => store.list<AuthoredPipeline>(space, PIPELINES_COLL);
        let m: string[] | null;

        if (method === 'GET' && NODE_TYPES_RE.test(url)) return json(NODE_TYPES);
        if (method === 'GET' && COMBINED.test(url)) return json(combined(all()));
        if (method === 'GET' && AUTHORED.test(url)) return json(all().map(summaryOf));
        if (method === 'GET' && (m = match(url, AUTHORED_RAW))) {
            return json(store.get<AuthoredPipeline>(space, PIPELINES_COLL, m[1]) ?? null);
        }
        if (method === 'POST' && (m = match(url, DRY_RUN))) {
            return json(dryRun(store.get<AuthoredPipeline>(space, PIPELINES_COLL, m[1])));
        }
        if (method === 'POST' && (m = match(url, RUN_TO))) {
            const files = (req.body as { files?: string[] })?.files ?? [];
            const f = store.get<AuthoredPipeline>(space, PIPELINES_COLL, m[1]);
            return json(runToNode(m[1], f, req.params['to'] ?? '', files));
        }
        if (method === 'GET' && FLOWS.test(url)) return json(all().map(summaryOf));
        if (method === 'GET' && (m = match(url, FLOW_GRAPH))) {
            return json(graphOf(store.get<AuthoredPipeline>(space, PIPELINES_COLL, m[1])));
        }
        if (method === 'POST' && AUTHORED.test(url)) {
            const f = req.body as AuthoredPipeline;
            // Mirrors the real backend: create 409s on an existing id (PipelineRoutes.createFlow) —
            // update is PUT, same split as components (components.handler.ts).
            if (store.get<AuthoredPipeline>(space, PIPELINES_COLL, f.name)) {
                return error(409, `authored flow "${f.name}" already exists (use PUT to update)`);
            }
            return json(store.put(space, PIPELINES_COLL, f.name, f));
        }
        if (method === 'PUT' && (m = match(url, AUTHORED_ID))) {
            const key = m[1];
            return json(store.put(space, PIPELINES_COLL, key, { ...(req.body as AuthoredPipeline), name: key }));
        }
        if (method === 'DELETE' && (m = match(url, AUTHORED_ID))) {
            // Referential integrity (R2) — e.g. a job triggering on this pipeline blocks the delete.
            const refs = store.referencesTo(space, PIPELINES_COLL, m[1]);
            if (refs.length) {
                const by = refs.map((r) => `${r.collection.replace('component:', '')}/${r.id}`).join(', ');
                return error(409, `pipeline "${m[1]}" is still referenced by: ${by}`);
            }
            store.delete(space, PIPELINES_COLL, m[1]);
            return json({ deleted: true });
        }

        if (method === 'GET' && ICON_MAP_RE.test(url)) {
            return json({ ...(store.get<IconMap>(space, 'config', 'icon-map') ?? {}) });
        }
        if (method === 'PUT' && ICON_MAP_RE.test(url)) {
            return json({ ...store.put(space, 'config', 'icon-map', req.body as IconMap) });
        }

        if (method === 'GET' && (PROV_BATCHES.test(url) || PROV.test(url))) return json([]);

        if (method === 'GET' && VIEWS.test(url)) return json(all().flatMap((f) => viewsOf(f)).map(viewSummaryOf));
        if (method === 'GET' && (m = match(url, VIEW_DATA))) return viewData(all(), m[1], Number(req.params['limit']) || 1000);
        if (method === 'GET' && (m = match(url, VIEW_NAME))) {
            const view = all().flatMap((f) => viewsOf(f)).find((v) => v.node.name === m![1]);
            return view ? json(viewSummaryOf(view)) : error(404, `no view '${m[1]}'`);
        }

        return undefined;
    };
}

/** Every `sink.view` node across a flow, paired with its owning flow name. */
function viewsOf(f: AuthoredPipeline): { node: AuthoredNode; flow: string }[] {
    return f.nodes.filter((n) => n.type === 'sink.view').map((node) => ({ node, flow: f.name }));
}

function viewSummaryOf(v: { node: AuthoredNode; flow: string }): PipelineViewSummary {
    return {
        store: v.node.name || v.node.id,
        flow: v.flow,
        source_store: [],
        has_derived_sql: true,
        defined_at: new Date().toISOString(),
    };
}

/** A bounded, pure-mock sample for a view's data preview — no real SQL execution. */
function viewData(flows: AuthoredPipeline[], name: string, limit: number): MockResponse {
    const v = flows.flatMap((f) => viewsOf(f)).find((v) => v.node.name === name);
    if (!v) return error(404, `no view '${name}'`);
    const rows = [
        { id: 1001, msisdn: '8801700000001', start_time: '2026-06-24 09:00:00', duration_s: 42 },
        { id: 1002, msisdn: '8801700000002', start_time: '2026-06-24 09:01:30', duration_s: 17 },
    ].slice(0, limit);
    const data: PipelineViewData = {
        view: name,
        columns: rows.length ? Object.keys(rows[0]) : [],
        rowCount: rows.length,
        capped: false,
        rows,
    };
    return json(data);
}

function summaryOf(f: AuthoredPipeline): PipelineSummary {
    return {
        name: f.name,
        active: f.active,
        nodeCount: f.nodes.length,
        edgeCount: f.edges.length,
        produces: [],
        consumes: [],
    };
}

/** rel → edge styling kind: terminal/keep flows are data; failure/unmatched/dropped are control. */
function edgeKind(rel: string): 'data' | 'control' | 'route' {
    if (rel.startsWith('route:')) return 'route';
    if (['failure', 'unmatched', 'dropped', 'gap', 'invalid'].includes(rel)) return 'control';
    return 'data';
}

function graphOf(f: AuthoredPipeline | undefined): PipelineGraph | null {
    if (!f) return null;
    const nodes: PipelineNode[] = f.nodes.map((n) => ({
        id: n.id,
        type: n.type,
        category: CATEGORY_OF.get(n.type) ?? 'TRANSFORM',
        label: n.name || n.type,
        name: n.name,
        description: n.description,
        use: n.use,
    }));
    return {
        name: f.name,
        active: f.active,
        nodes,
        edges: f.edges.map((e) => ({ from: e.from, to: e.to, rel: e.rel, kind: edgeKind(e.rel) })),
        produces: [],
        consumes: [],
    };
}

/** Build the combined topology: every pipeline's nodes + edges, namespaced `<pipeline>/<node>`. */
function combined(flows: AuthoredPipeline[]): unknown {
    const nodes = flows.flatMap((f) =>
        f.nodes.map((n) => ({
            id: `${f.name}/${n.id}`,
            type: n.type,
            category: CATEGORY_OF.get(n.type) ?? 'TRANSFORM',
            label: n.name || n.id,
            name: n.name,
            use: n.use,
            flow: f.name,
        })),
    );
    const edges = flows.flatMap((f) =>
        f.edges.map((e) => ({
            from: `${f.name}/${e.from}`,
            to: `${f.name}/${e.to}`,
            rel: e.rel,
            kind: edgeKind(e.rel),
            flow: f.name,
        })),
    );
    return { flows: flows.map((f) => ({ name: f.name, active: f.active })), nodes, edges, links: [] };
}

function dryRun(f: AuthoredPipeline | undefined): PipelineDryRunResult {
    const rows = [
        { id: 1001, msisdn: '8801700000001', start_time: '2026-06-24 09:00:00', duration_s: 42 },
        { id: 1002, msisdn: '8801700000002', start_time: '2026-06-24 09:01:30', duration_s: 17 },
    ];
    const seedNode = f?.nodes.find((n) => n.type.startsWith('collector'))?.id ?? f?.nodes[0]?.id ?? '';
    const nodes = (f?.nodes ?? [])
        .filter((n) => !n.type.startsWith('sink'))
        .map((n) => ({ node: n.id, type: n.type, relations: [{ rel: 'success', rowCount: rows.length, rows }] }));
    const sinks = (f?.nodes ?? [])
        .filter((n) => n.type.startsWith('sink'))
        .map((n) => ({ node: n.id, store: n.name || n.id, rowCount: rows.length, rows }));
    return { seedNode, nodes, sinks };
}

/** The nodes from the seed source down to (and including) `toNode`, in declaration order — the run subgraph. */
function subgraphTo(f: AuthoredPipeline | undefined, toNode: string): AuthoredNode[] {
    if (!f) return [];
    const incoming = new Map<string, string[]>();
    for (const e of f.edges) {
        const list = incoming.get(e.to) ?? [];
        list.push(e.from);
        incoming.set(e.to, list);
    }
    const keep = new Set<string>();
    const stack = [toNode];
    while (stack.length) {
        const cur = stack.pop()!;
        if (keep.has(cur)) continue;
        keep.add(cur);
        for (const p of incoming.get(cur) ?? []) stack.push(p);
    }
    return f.nodes.filter((n) => keep.has(n.id));
}

/**
 * Run-to-here: walk seed→toNode and emit per-relation counts + a bounded sample, plus the scratch Parquet
 * the run "landed". A parser splits success/unmatched; a transform splits kept/dropped — the matched/rejected
 * feedback the grammar/rules loop needs. Pure mock data; no real parse.
 */
function runToNode(name: string, f: AuthoredPipeline | undefined, toNode: string, files: string[]): PipelineRunResult {
    const matched = [
        { id: 1001, msisdn: '8801700000001', start_time: '2026-06-24 09:00:00', duration_s: 42 },
        { id: 1002, msisdn: '8801700000002', start_time: '2026-06-24 09:01:30', duration_s: 17 },
        { id: 1003, msisdn: '8801700000003', start_time: '2026-06-24 09:03:11', duration_s: 8 },
    ];
    const path = subgraphTo(f, toNode);
    const relations: PipelineRunRelation[] = [];
    for (const n of path) {
        const cat = CATEGORY_OF.get(n.type);
        if (cat === 'SOURCE') {
            const rows = (files.length ? files : ['(built-in sample)']).map((p, i) => ({ file: p, bytes: 10240 + i * 512 }));
            relations.push({ node: n.id, rel: 'success', rowCount: rows.length, rows });
        } else if (cat === 'PARSE') {
            relations.push({ node: n.id, rel: 'success', rowCount: matched.length, rows: matched });
            relations.push({ node: n.id, rel: 'unmatched', rowCount: 1, rows: [{ line: 7, raw: '##trailer,checksum,0xdeadbeef' }] });
        } else if (cat === 'TRANSFORM') {
            relations.push({ node: n.id, rel: 'kept', rowCount: 2, rows: matched.slice(0, 2) });
            relations.push({ node: n.id, rel: 'dropped', rowCount: 1, rows: matched.slice(2) });
        }
    }
    const toNodeObj = f?.nodes.find((n) => n.id === toNode);
    // The landed Parquet reflects the target node's primary output (success/kept), not its reject branch.
    const atTarget = relations.filter((r) => r.node === toNode);
    const primary = atTarget.find((r) => r.rel === 'success' || r.rel === 'kept') ?? atTarget[atTarget.length - 1];
    const output = {
        store: toNodeObj?.name || toNode,
        format: 'PARQUET',
        path: `data/_scratch/${name}/${toNode}/part-0001.parquet`,
        rowCount: primary?.rowCount ?? 0,
    };
    return {
        seedNode: path[0]?.id ?? '',
        toNode,
        files,
        relations,
        output,
        warnings: files.length ? [] : ['No files selected — ran over a bounded built-in sample.'],
    };
}
