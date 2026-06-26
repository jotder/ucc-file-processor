import { HttpEvent, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import {
    AuthoredFlow,
    AuthoredNode,
    FlowDryRunResult,
    FlowGraph,
    FlowNode,
    FlowNodeType,
    FlowRunRelation,
    FlowRunResult,
    FlowSummary,
} from './flows.service';
import { ComponentDef } from './components.service';

/**
 * PROTOTYPE-ONLY mock for the Pipelines graph editor (the `flows` feature). Serves the node-type palette,
 * authored-flow CRUD, the read-only graph projection, dry-run, and per-processor test entirely from an
 * in-memory store so the editor is built and iterated UI-first with ZERO backend. Gated on
 * {@code environment.mockFlows}; registered before the space/error interceptors so it short-circuits. Remove
 * (or flip the flag) once the real flow backend is wired — the contract (FlowsService types) is unchanged.
 *
 * <p>The palette is the agreed processor taxonomy: Collector (file/db/stream), Parser (DSV/ASN.1/Text/Other),
 * Transformer (record/route/filter/aggregation/alert), plus a Writer sink (pipelines need an output).
 */

/** The processor palette — grouped by category (Collector=SOURCE, Parser=PARSE, Transformer=TRANSFORM, Writer=SINK). */
const NODE_TYPES: FlowNodeType[] = [
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

/** In-memory authored-flow store, seeded with two sample pipelines so open/edit works immediately. */
const STORE = new Map<string, AuthoredFlow>();
STORE.set('cdr_ingest', {
    name: 'cdr_ingest',
    active: true,
    nodes: [
        { id: 'collect', type: 'collector.file', name: 'Collect CDR drops', use: 'connections/cdr_sftp_prod', config: { include: 'glob:**/*.csv.gz' } },
        { id: 'parse', type: 'parser.dsv', name: 'Parse CSV', config: { delimiter: ',', header: true } },
        { id: 'filter', type: 'transform.filter', name: 'Drop test rows', config: { predicate: "msisdn NOT LIKE '0000%'" } },
        { id: 'write', type: 'sink.file', name: 'CDR parquet', config: { format: 'PARQUET', partition_by: 'event_date' } },
    ],
    edges: [
        { from: 'collect', rel: 'success', to: 'parse' },
        { from: 'parse', rel: 'success', to: 'filter' },
        { from: 'filter', rel: 'kept', to: 'write' },
    ],
});
STORE.set('subscriber_load', {
    name: 'subscriber_load',
    active: false,
    nodes: [
        { id: 'extract', type: 'collector.database', name: 'Extract subscribers' },
        { id: 'daily', type: 'transform.aggregate', name: 'Daily counts' },
        { id: 'load', type: 'sink.database', name: 'Load summary' },
    ],
    edges: [
        { from: 'extract', rel: 'success', to: 'daily' },
        { from: 'daily', rel: 'success', to: 'load' },
    ],
});

/** Seeded registry components per kind, so the in-graph "choose a grammar/transform/sink" picker has options. */
const COMPONENT_STORE: Record<string, ComponentDef[]> = {
    grammar: [
        { type: 'grammar', name: 'cdr_csv', ref: 'grammar/cdr_csv', content: { delimiter: ',', has_header: true } },
        { type: 'grammar', name: 'pipe_delimited', ref: 'grammar/pipe_delimited', content: { delimiter: '|', has_header: false } },
    ],
    schema: [
        { type: 'schema', name: 'cdr_record', ref: 'schema/cdr_record', content: { fields: [{ name: 'msisdn', type: 'string' }, { name: 'duration_s', type: 'integer' }] } },
    ],
    transform: [
        { type: 'transform', name: 'drop_test_rows', ref: 'transform/drop_test_rows', content: { type: 'transform.filter', where: "msisdn NOT LIKE '0000%'" } },
    ],
    sink: [
        { type: 'sink', name: 'cdr_parquet', ref: 'sink/cdr_parquet', content: { type: 'sink.persistent', format: 'parquet', partitions: ['event_date'] } },
    ],
};

const LATENCY_MS = 200;

const FLOWS = /\/flows$/;
const NODE_TYPES_RE = /\/flows\/node-types$/;
const COMBINED = /\/flows\/combined$/;
const AUTHORED = /\/flows\/authored$/;
const AUTHORED_RAW = /\/flows\/authored\/([^/]+)\/raw$/;
const DRY_RUN = /\/flows\/authored\/([^/]+)\/dry-run$/;
const RUN_TO = /\/flows\/authored\/([^/]+)\/run$/;
const AUTHORED_ID = /\/flows\/authored\/([^/]+)$/;
const FLOW_GRAPH = /\/flows\/([^/]+)\/graph$/;
const PROV_BATCHES = /\/provenance\/batches$/;
const PROV = /\/provenance$/;
const COMPONENT_TEST = /\/components\/([^/]+)\/([^/]+)\/test$/;
const COMPONENT_ONE = /\/components\/([^/]+)\/([^/]+)$/;
const COMPONENTS = /\/components\/([^/]+)$/;

export const flowMockInterceptor: HttpInterceptorFn = (req, next) => {
    if (!(environment as { mockFlows?: boolean }).mockFlows) return next(req);
    const url = req.url;
    let m: RegExpMatchArray | null;

    if (req.method === 'GET' && NODE_TYPES_RE.test(url)) return reply(NODE_TYPES);
    if (req.method === 'GET' && COMBINED.test(url)) return reply(combined());
    if (req.method === 'GET' && AUTHORED.test(url)) return reply(summaries());
    if (req.method === 'GET' && (m = url.match(AUTHORED_RAW))) return reply(STORE.get(id(m)) ?? null);
    if (req.method === 'POST' && (m = url.match(DRY_RUN))) return reply(dryRun(id(m)));
    if (req.method === 'POST' && (m = url.match(RUN_TO))) {
        const files = (req.body as { files?: string[] })?.files ?? [];
        return reply(runToNode(id(m), req.params.get('to') ?? '', files));
    }
    if (req.method === 'GET' && FLOWS.test(url)) return reply(summaries());
    if (req.method === 'GET' && (m = url.match(FLOW_GRAPH))) return reply(graphOf(id(m)));

    if (req.method === 'POST' && AUTHORED.test(url)) {
        const f = req.body as AuthoredFlow;
        STORE.set(f.name, f);
        return reply(f);
    }
    if (req.method === 'PUT' && (m = url.match(AUTHORED_ID))) {
        const key = id(m);
        STORE.set(key, { ...(req.body as AuthoredFlow), name: key });
        return reply(STORE.get(key));
    }
    if (req.method === 'DELETE' && (m = url.match(AUTHORED_ID))) {
        STORE.delete(id(m));
        return reply({ deleted: true });
    }
    if (req.method === 'POST' && (m = url.match(COMPONENT_TEST))) {
        return reply(componentTest(decodeURIComponent(m[1]), decodeURIComponent(m[2])));
    }
    // Component registry CRUD (grammar/schema/transform/sink) — backs the in-graph choose-or-create binding.
    if (req.method === 'GET' && (m = url.match(COMPONENT_ONE))) return reply(componentGet(dec(m[1]), dec(m[2])));
    if (req.method === 'PUT' && (m = url.match(COMPONENT_ONE))) return reply(componentSave(dec(m[1]), req.body, dec(m[2])));
    if (req.method === 'GET' && (m = url.match(COMPONENTS))) return reply(componentList(dec(m[1])));
    if (req.method === 'POST' && (m = url.match(COMPONENTS))) return reply(componentSave(dec(m[1]), req.body));

    if (req.method === 'GET' && (PROV_BATCHES.test(url) || PROV.test(url))) return reply([]);

    return next(req);
};

function reply<T>(body: T): Observable<HttpEvent<unknown>> {
    return of(new HttpResponse({ status: 200, body })).pipe(delay(LATENCY_MS));
}

function id(m: RegExpMatchArray): string {
    return decodeURIComponent(m[1]);
}

function summaries(): FlowSummary[] {
    return [...STORE.values()].map((f) => ({
        name: f.name,
        active: f.active,
        nodeCount: f.nodes.length,
        edgeCount: f.edges.length,
        produces: [],
        consumes: [],
    }));
}

/** rel → edge styling kind: terminal/keep flows are data; failure/unmatched/dropped are control. */
function edgeKind(rel: string): 'data' | 'control' | 'route' {
    if (rel.startsWith('route:')) return 'route';
    if (['failure', 'unmatched', 'dropped', 'gap', 'invalid'].includes(rel)) return 'control';
    return 'data';
}

function graphOf(name: string): FlowGraph | null {
    const f = STORE.get(name);
    if (!f) return null;
    const nodes: FlowNode[] = f.nodes.map((n) => ({
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

function combined() {
    return {
        flows: [...STORE.values()].map((f) => ({ name: f.name, active: f.active })),
        nodes: [],
        edges: [],
        links: [],
    };
}

function dryRun(name: string): FlowDryRunResult {
    const f = STORE.get(name);
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
function subgraphTo(f: AuthoredFlow | undefined, toNode: string): AuthoredNode[] {
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
function runToNode(name: string, toNode: string, files: string[]): FlowRunResult {
    const f = STORE.get(name);
    const matched = [
        { id: 1001, msisdn: '8801700000001', start_time: '2026-06-24 09:00:00', duration_s: 42 },
        { id: 1002, msisdn: '8801700000002', start_time: '2026-06-24 09:01:30', duration_s: 17 },
        { id: 1003, msisdn: '8801700000003', start_time: '2026-06-24 09:03:11', duration_s: 8 },
    ];
    const path = subgraphTo(f, toNode);
    const relations: FlowRunRelation[] = [];
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

function dec(s: string): string {
    return decodeURIComponent(s);
}

function componentList(type: string): ComponentDef[] {
    return COMPONENT_STORE[type] ?? [];
}

function componentGet(type: string, id: string): ComponentDef | null {
    return (COMPONENT_STORE[type] ?? []).find((d) => d.name === id) ?? null;
}

/** Create (POST, id in body) or replace (PUT, id in URL) a registry component; mirrors the real id→name split. */
function componentSave(type: string, body: unknown, idFromUrl?: string): ComponentDef {
    const content = { ...((body as Record<string, unknown>) ?? {}) };
    const name = String(idFromUrl ?? content['id'] ?? 'unnamed');
    delete content['id'];
    const def: ComponentDef = { type, name, ref: `${type}/${name}`, content };
    const list = COMPONENT_STORE[type] ?? (COMPONENT_STORE[type] = []);
    const i = list.findIndex((d) => d.name === name);
    if (i >= 0) list[i] = def;
    else list.push(def);
    return def;
}

function componentTest(type: string, idRef: string) {
    return {
        type,
        id: idRef,
        ok: true,
        detail: `${type} "${idRef}" validated against a bounded sample`,
        rowCount: 2,
        rows: [
            { id: 1001, msisdn: '8801700000001', duration_s: 42 },
            { id: 1002, msisdn: '8801700000002', duration_s: 17 },
        ],
    };
}
