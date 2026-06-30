import { HttpEvent, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import {
    AuthoredNode,
    AuthoredPipeline,
    PipelineDryRunResult,
    PipelineGraph,
    PipelineNode,
    PipelineNodeType,
    PipelineRunRelation,
    PipelineRunResult,
    PipelineSummary,
} from './pipelines.service';
import { ComponentDef, ParserPreview, ParserTreeNode } from './components.service';
import { IconMap } from './icon-map.service';
import { NODE_KIND_COLORS } from '../theme/chart-tokens';

/**
 * PROTOTYPE-ONLY mock for the Pipelines graph editor. Serves the node-type palette,
 * authored-pipeline CRUD, the read-only graph projection, dry-run, and per-processor test entirely from an
 * in-memory store so the editor is built and iterated UI-first with ZERO backend. Gated on
 * {@code environment.mockFlows}; registered before the space/error interceptors so it short-circuits. Remove
 * (or flip the flag) once the real backend is wired — the contract (PipelinesService types) is unchanged.
 *
 * <p>The palette is the agreed processor taxonomy: Collector (file/db/stream), Parser (DSV/ASN.1/Text/Other),
 * Transformer (record/route/filter/aggregation/alert), plus a Writer sink (pipelines need an output).
 */

/** The processor palette — grouped by category (Collector=SOURCE, Parser=PARSE, Transformer=TRANSFORM, Writer=SINK). */
const NODE_TYPES: PipelineNodeType[] = [
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

/** In-memory authored-pipeline store, seeded with two sample pipelines so open/edit works immediately. */
const STORE = new Map<string, AuthoredPipeline>();
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

/** Configurable processor-icon map (mutable). Seeded with category defaults + a few sub-type overrides. */
const C = NODE_KIND_COLORS; // category accent colours, sourced from the canvas token owner
const ICON_MAP: IconMap = {
    SOURCE: { glyph: 'arrow-in', color: C.SOURCE },
    PARSE: { glyph: 'lines', color: C.SCHEMA },
    TRANSFORM: { glyph: 'transform', color: C.ENRICHMENT },
    SINK: { glyph: 'cylinder', color: C.TABLE },
    CONTROL: { glyph: 'bell', color: C.KPI },
    'collector.file': { glyph: 'file', color: C.SOURCE },
    'collector.database': { glyph: 'database', color: C.SOURCE },
    'collector.stream': { glyph: 'stream', color: C.SOURCE },
    'transform.filter': { glyph: 'filter', color: C.ENRICHMENT },
    'transform.route': { glyph: 'route', color: C.ENRICHMENT },
    'transform.aggregate': { glyph: 'sigma', color: C.ENRICHMENT },
    'transform.alert': { glyph: 'bell', color: C.KPI },
    'sink.file': { glyph: 'write', color: C.TABLE },
    'sink.database': { glyph: 'database', color: C.TABLE },
};

/** In-memory ASN.1 schema-module library (mutable; locally-uploaded modules are added here). */
const ASN1_MODULES: Record<string, string> = {
    'cdr_3gpp_ts32297': [
        '-- 3GPP TS 32.297 CDR record (abridged sample)',
        'CallEventRecord ::= SEQUENCE {',
        '    recordType        [0] INTEGER,',
        '    servedIMSI        [1] OCTET STRING,',
        '    callDuration      [2] INTEGER,',
        '    recordOpeningTime [3] GeneralizedTime',
        '}',
    ].join('\n'),
    'map_rel99': [
        '-- MAP Rel-99 (abridged sample)',
        'MAP-Protocol DEFINITIONS ::= BEGIN',
        '    SubscriberInfo ::= SEQUENCE {',
        '        imsi      [0] OCTET STRING,',
        '        msisdn    [1] OCTET STRING OPTIONAL',
        '    }',
        'END',
    ].join('\n'),
};

const LATENCY_MS = 200;

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
const COMPONENT_TEST = /\/components\/([^/]+)\/([^/]+)\/test$/;
const GRAMMAR_PREVIEW = /\/components\/grammar\/preview$/;
const COMPONENT_ONE = /\/components\/([^/]+)\/([^/]+)$/;
const COMPONENTS = /\/components\/([^/]+)$/;
const ICON_MAP_RE = /\/config\/icon-map$/;
const ASN1_MODULES_RE = /\/asn1\/modules$/;
const ASN1_MODULE_ONE = /\/asn1\/modules\/([^/]+)$/;

export const pipelineMockInterceptor: HttpInterceptorFn = (req, next) => {
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
        const f = req.body as AuthoredPipeline;
        STORE.set(f.name, f);
        return reply(f);
    }
    if (req.method === 'PUT' && (m = url.match(AUTHORED_ID))) {
        const key = id(m);
        STORE.set(key, { ...(req.body as AuthoredPipeline), name: key });
        return reply(STORE.get(key));
    }
    if (req.method === 'DELETE' && (m = url.match(AUTHORED_ID))) {
        STORE.delete(id(m));
        return reply({ deleted: true });
    }
    if (req.method === 'POST' && GRAMMAR_PREVIEW.test(url)) {
        const b = (req.body ?? {}) as { parserType?: string; content?: Record<string, unknown>; sampleText?: string };
        return reply(parsePreview(b.parserType ?? 'dsv', b.content ?? {}, b.sampleText ?? ''));
    }
    if (req.method === 'POST' && (m = url.match(COMPONENT_TEST))) {
        return reply(componentTest(decodeURIComponent(m[1]), decodeURIComponent(m[2])));
    }
    // Component registry CRUD (grammar/schema/transform/sink) — backs the in-graph choose-or-create binding.
    if (req.method === 'GET' && (m = url.match(COMPONENT_ONE))) return reply(componentGet(dec(m[1]), dec(m[2])));
    if (req.method === 'PUT' && (m = url.match(COMPONENT_ONE))) return reply(componentSave(dec(m[1]), req.body, dec(m[2])));
    if (req.method === 'GET' && (m = url.match(COMPONENTS))) return reply(componentList(dec(m[1])));
    if (req.method === 'POST' && (m = url.match(COMPONENTS))) return reply(componentSave(dec(m[1]), req.body));

    if (req.method === 'GET' && ICON_MAP_RE.test(url)) return reply({ ...ICON_MAP });
    if (req.method === 'PUT' && ICON_MAP_RE.test(url)) {
        for (const k of Object.keys(ICON_MAP)) delete ICON_MAP[k];
        Object.assign(ICON_MAP, req.body as IconMap);
        return reply({ ...ICON_MAP });
    }

    // ASN.1 schema-module library — backs the parser config's `schema_spec` picker (download + upload).
    if (req.method === 'GET' && ASN1_MODULES_RE.test(url)) {
        return reply(Object.keys(ASN1_MODULES).map((name) => ({ name })));
    }
    if (req.method === 'GET' && (m = url.match(ASN1_MODULE_ONE))) {
        const name = dec(m[1]);
        return reply(name in ASN1_MODULES ? { name, text: ASN1_MODULES[name] } : null);
    }
    if (req.method === 'POST' && ASN1_MODULES_RE.test(url)) {
        const b = (req.body ?? {}) as { name?: string; text?: string };
        const name = String(b.name ?? 'uploaded.asn1');
        ASN1_MODULES[name] = String(b.text ?? '');
        return reply({ name });
    }

    if (req.method === 'GET' && (PROV_BATCHES.test(url) || PROV.test(url))) return reply([]);

    return next(req);
};

function reply<T>(body: T): Observable<HttpEvent<unknown>> {
    return of(new HttpResponse({ status: 200, body })).pipe(delay(LATENCY_MS));
}

function id(m: RegExpMatchArray): string {
    return decodeURIComponent(m[1]);
}

function summaries(): PipelineSummary[] {
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

function graphOf(name: string): PipelineGraph | null {
    const f = STORE.get(name);
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

/** Build the combined topology: every flow's nodes + edges, namespaced `<flow>/<node>` and tagged with `flow`. */
function combined() {
    const flows = [...STORE.values()];
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

function dryRun(name: string): PipelineDryRunResult {
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
function runToNode(name: string, toNode: string, files: string[]): PipelineRunResult {
    const f = STORE.get(name);
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

/** Hierarchical parser ids preview as a tree; everything else previews as a flat table. */
const HIERARCHICAL = new Set(['asn1', 'json', 'xml']);

/**
 * Mock the parse of `sampleText` under a parser config. DSV genuinely splits the pasted text on its
 * configured delimiter (so the test loop feels live); the other tabular formats return canned rows, and the
 * hierarchical formats return a small record forest for the tree view. Pure mock — no real codec.
 */
function parsePreview(parserType: string, content: Record<string, unknown>, sampleText: string): ParserPreview {
    if (HIERARCHICAL.has(parserType)) {
        return { kind: 'tree', recordCount: SAMPLE_TREE.length, nodes: SAMPLE_TREE };
    }
    if (parserType === 'dsv' && sampleText.trim()) {
        return dsvPreview(content, sampleText);
    }
    const rows = [
        { id: 1001, msisdn: '8801700000001', start_time: '2026-06-24 09:00:00', duration_s: 42 },
        { id: 1002, msisdn: '8801700000002', start_time: '2026-06-24 09:01:30', duration_s: 17 },
        { id: 1003, msisdn: '8801700000003', start_time: '2026-06-24 09:03:11', duration_s: 8 },
    ];
    return { kind: 'table', columns: Object.keys(rows[0]), rows, rowCount: rows.length, rejectedRows: 0 };
}

/** Split the pasted sample on the configured delimiter, honouring the header-position property. */
function dsvPreview(content: Record<string, unknown>, sampleText: string): ParserPreview {
    const delim = String(content['column_delimiter'] || ',');
    const lines = sampleText.replace(/\r\n/g, '\n').split('\n').filter((l) => l.length > 0);
    const hasHeader = String(content['header_position'] ?? 'top') === 'top';
    const headerCells = lines.length ? lines[0].split(delim) : [];
    const columns = hasHeader && headerCells.length
        ? headerCells.map((c) => c.trim())
        : headerCells.map((_, i) => `c${i}`);
    const bodyLines = hasHeader ? lines.slice(1) : lines;
    let rejectedRows = 0;
    const rows: Record<string, unknown>[] = [];
    for (const line of bodyLines) {
        const cells = line.split(delim);
        if (cells.length !== columns.length) { rejectedRows++; continue; }
        const row: Record<string, unknown> = {};
        columns.forEach((c, i) => (row[c] = cells[i]?.trim() ?? ''));
        rows.push(row);
    }
    return { kind: 'table', columns, rows, rowCount: rows.length, rejectedRows };
}

/** A tiny two-record forest mirroring the seeded CDR sample, for the hierarchical (ASN.1/JSON/XML) tree view. */
const SAMPLE_TREE: ParserTreeNode[] = [
    {
        label: 'record[0]', type: 'SEQUENCE', children: [
            { label: 'id', type: 'INTEGER', value: '1001' },
            { label: 'msisdn', type: 'string', value: '8801700000001' },
            { label: 'call', type: 'SEQUENCE', children: [
                { label: 'start_time', type: 'timestamp', value: '2026-06-24 09:00:00' },
                { label: 'duration_s', type: 'INTEGER', value: '42' },
            ] },
        ],
    },
    {
        label: 'record[1]', type: 'SEQUENCE', children: [
            { label: 'id', type: 'INTEGER', value: '1002' },
            { label: 'msisdn', type: 'string', value: '8801700000002' },
            { label: 'call', type: 'SEQUENCE', children: [
                { label: 'start_time', type: 'timestamp', value: '2026-06-24 09:01:30' },
                { label: 'duration_s', type: 'INTEGER', value: '17' },
            ] },
        ],
    },
];

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
