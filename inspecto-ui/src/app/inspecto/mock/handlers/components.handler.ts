import type { ComponentDef, ParserPreview, ParserTreeNode } from '../../api/components.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * Unified `/components/*` + `/asn1/*` mock domain — the merge of the old `studio-mock` (dataset /
 * widget / dashboard kinds) and the registry half of `pipeline-mock` (grammar / schema / transform /
 * sink kinds + per-component test + grammar preview + the ASN.1 module library), now backed by the
 * persistent {@link MockStore} instead of two module-level constants. The old interceptor-ordering
 * hack (studio before pipeline so the Studio kinds weren't swallowed) is gone — one handler owns the
 * whole family. DELETE now enforces referential integrity (409 + referencers), mirroring the backend.
 */

const STUDIO_KINDS = new Set(['dataset', 'widget', 'dashboard']);

/** MockStore collection for a component kind. */
export const componentCollection = (kind: string): string => `component:${kind}`;

const COMPONENT_TEST = /\/components\/([^/]+)\/([^/]+)\/test$/;
const GRAMMAR_PREVIEW = /\/components\/grammar\/preview$/;
const COMPONENT_ONE = /\/components\/([^/]+)\/([^/]+)$/;
const COMPONENTS = /\/components\/([^/]+)$/;
const ASN1_MODULES = /\/asn1\/modules$/;
const ASN1_MODULE_ONE = /\/asn1\/modules\/([^/]+)$/;

export function componentsHandler(flags: MockFlags): MockHandler {
    const enabledFor = (kind: string): boolean =>
        STUDIO_KINDS.has(kind) ? !!flags.mockStudio : !!flags.mockFlows;

    return (req: MockRequest, store: MockStore) => {
        const { method, url, space } = req;
        let m: string[] | null;

        if (flags.mockFlows) {
            if (method === 'POST' && GRAMMAR_PREVIEW.test(url)) {
                const b = (req.body ?? {}) as { parserType?: string; content?: Record<string, unknown>; sampleText?: string };
                return json(parsePreview(b.parserType ?? 'dsv', b.content ?? {}, b.sampleText ?? ''));
            }
            if (method === 'POST' && (m = match(url, COMPONENT_TEST))) return json(componentTest(m[1], m[2]));
            if (method === 'GET' && ASN1_MODULES.test(url)) {
                return json(store.list<{ name: string }>(space, 'asn1-module').map(({ name }) => ({ name })));
            }
            if (method === 'GET' && (m = match(url, ASN1_MODULE_ONE))) {
                return json(store.get(space, 'asn1-module', m[1]) ?? null);
            }
            if (method === 'POST' && ASN1_MODULES.test(url)) {
                const b = (req.body ?? {}) as { name?: string; text?: string };
                const name = String(b.name ?? 'uploaded.asn1');
                store.put(space, 'asn1-module', name, { name, text: String(b.text ?? '') });
                return json({ name });
            }
        }

        if ((m = match(url, COMPONENT_ONE)) && enabledFor(m[1])) {
            const [, kind, id] = m;
            const coll = componentCollection(kind);
            if (method === 'GET') return json(store.get<ComponentDef>(space, coll, id) ?? null);
            if (method === 'PUT') return json(save(store, space, kind, req.body, id));
            if (method === 'DELETE') {
                const refs = store.referencesTo(space, coll, id);
                if (refs.length) {
                    const by = refs.map((r) => `${r.collection.replace('component:', '')}/${r.id}`).join(', ');
                    return error(409, `${kind} "${id}" is still referenced by: ${by}`);
                }
                store.delete(space, coll, id);
                return json({ deleted: true });
            }
        }
        if ((m = match(url, COMPONENTS)) && enabledFor(m[1])) {
            const kind = m[1];
            if (method === 'GET') return json(store.list<ComponentDef>(space, componentCollection(kind)));
            if (method === 'POST') return json(save(store, space, kind, req.body));
        }
        return undefined;
    };
}

/** Create (POST, id in body) or replace (PUT, id in URL) — mirrors the real id→name split. */
function save(store: MockStore, space: string, kind: string, body: unknown, idFromUrl?: string): ComponentDef {
    const content = { ...((body as Record<string, unknown>) ?? {}) };
    const name = String(idFromUrl ?? content['id'] ?? 'unnamed');
    delete content['id'];
    const def: ComponentDef = { type: kind, name, ref: `${kind}/${name}`, content };
    return store.put(space, componentCollection(kind), name, def);
}

function componentTest(type: string, idRef: string): unknown {
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

/** Hierarchical parser ids preview as a tree; everything else previews as a flat table. */
const HIERARCHICAL = new Set(['asn1', 'json', 'xml']);

/**
 * Mock the parse of `sampleText` under a parser config. DSV genuinely splits the pasted text on its
 * configured delimiter (so the test loop feels live); the other tabular formats return canned rows, and
 * the hierarchical formats return a small record forest for the tree view. Pure mock — no real codec.
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
