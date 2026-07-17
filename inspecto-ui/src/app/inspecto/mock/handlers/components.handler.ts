import type { ComponentDef, ComponentVersion, ParserPreview, ParserTreeNode } from '../../api/components.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { emitAudit } from '../signals';

/**
 * Unified `/components/*` + `/asn1/*` mock domain — the merge of the old `studio-mock` (dataset /
 * widget / dashboard kinds) and the registry half of `pipeline-mock` (grammar / schema / transform /
 * sink kinds + per-component test + grammar preview + the ASN.1 module library), now backed by the
 * persistent {@link MockStore} instead of two module-level constants. The old interceptor-ordering
 * hack (studio before pipeline so the Studio kinds weren't swallowed) is gone — one handler owns the
 * whole family. DELETE now enforces referential integrity (409 + referencers), mirroring the backend.
 */

const STUDIO_KINDS = new Set(['dataset', 'query', 'widget', 'dashboard', 'requirement', 'reconciliation', 'link-analysis-view', 'geo-map-view']);

/** MockStore collection for a component kind. */
export const componentCollection = (kind: string): string => `component:${kind}`;
/** MET-5: MockStore collection holding a kind's archived prior copies (mirrors the backend `.history/`). */
const historyCollection = (kind: string): string => `component-history:${kind}`;
/** Keep the newest N archived copies per component (mirrors the backend keep bound). */
const HISTORY_KEEP = 10;

/** One archived copy as stored in the mock history collection. */
interface StoredVersion { id: string; version: number; savedAt: string; contentHash: string; content: Record<string, unknown>; }

const COMPONENT_TEST = /\/components\/([^/]+)\/([^/]+)\/test$/;
const COMPONENT_VERSIONS = /\/components\/([^/]+)\/([^/]+)\/versions$/;
const COMPONENT_RESTORE = /\/components\/([^/]+)\/([^/]+)\/versions\/([^/]+)\/restore$/;
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

        // MET-5 version history (before COMPONENT_ONE — these are longer, anchored paths).
        if (method === 'POST' && (m = match(url, COMPONENT_RESTORE)) && enabledFor(m[1])) {
            return restoreVersion(store, space, m[1], m[2], m[3]);
        }
        if (method === 'GET' && (m = match(url, COMPONENT_VERSIONS)) && enabledFor(m[1])) {
            return json(listVersions(store, space, m[1], m[2]));
        }

        if ((m = match(url, COMPONENT_ONE)) && enabledFor(m[1])) {
            const [, kind, id] = m;
            const coll = componentCollection(kind);
            if (method === 'GET') return json(store.get<ComponentDef>(space, coll, id) ?? null);
            if (method === 'PUT') {
                // Mirrors the real backend: update requires the id to already exist (404 otherwise —
                // create is POST). Without this check the mock silently upserted on PUT, masking the
                // same class of offline/live divergence the POST-409 fix (item 3) was written to catch.
                if (!store.get<ComponentDef>(space, coll, id)) {
                    return error(404, `${kind} "${id}" not found`);
                }
                const saved = putComponent(store, space, kind, req.body, id);
                emitAudit(store, space, {
                    action: `${kind}.updated`, category: 'config', targetType: kind, targetId: id,
                    message: `Updated ${kind} ${id}`,
                });
                return json(saved);
            }
            if (method === 'DELETE') {
                const refs = store.referencesTo(space, coll, id);
                if (refs.length) {
                    const by = refs.map((r) => `${r.collection.replace('component:', '')}/${r.id}`).join(', ');
                    return error(409, `${kind} "${id}" is still referenced by: ${by}`);
                }
                deleteComponent(store, space, kind, id);   // purges archived versions too (MET-5)
                emitAudit(store, space, {
                    action: `${kind}.deleted`, category: 'destructive', targetType: kind, targetId: id,
                    message: `Deleted ${kind} ${id}`,
                });
                return json({ deleted: true });
            }
        }
        if ((m = match(url, COMPONENTS)) && enabledFor(m[1])) {
            const kind = m[1];
            if (method === 'GET') return json(store.list<ComponentDef>(space, componentCollection(kind)));
            if (method === 'POST') {
                // Mirror the real backend: create 409s on an existing id (update is PUT). Keeping the
                // mock honest here is what surfaces create-on-edit bugs offline.
                const id = String((req.body as Record<string, unknown> | null)?.['id'] ?? 'unnamed');
                if (store.get<ComponentDef>(space, componentCollection(kind), id)) {
                    return error(409, `${kind} "${id}" already exists`);
                }
                const created = putComponent(store, space, kind, req.body);
                emitAudit(store, space, {
                    action: `${kind}.created`, category: 'config', targetType: kind, targetId: created.name,
                    message: `Created ${kind} ${created.name}`,
                });
                return json(created);
            }
        }
        return undefined;
    };
}

/** Create (POST, id in body) or replace (PUT, id in URL) — mirrors the real id→name split. Exported so
 *  sibling domain handlers persisting a component kind through their own routes (expectations) share the
 *  MET-5 archive-on-save behaviour instead of re-rolling it. */
export function putComponent(store: MockStore, space: string, kind: string, body: unknown, idFromUrl?: string): ComponentDef {
    const content = { ...((body as Record<string, unknown>) ?? {}) };
    const name = String(idFromUrl ?? content['id'] ?? 'unnamed');
    delete content['id'];
    // MET-5: archive the outgoing copy before overwriting it (a create over nothing archives nothing).
    const prior = store.get<ComponentDef>(space, componentCollection(kind), name);
    if (prior) archiveVersion(store, space, kind, name, prior.content);
    const def: ComponentDef = { type: kind, name, ref: `${kind}/${name}`, content };
    return store.put(space, componentCollection(kind), name, def);
}

/** Write a component WITHOUT archiving — for result-stamp updates (e.g. an Expectation's `lastResult`
 *  after a run-check), which are not authoring edits (mirrors the backend's `write(…, archive=false)`). */
export function putComponentQuiet(store: MockStore, space: string, kind: string, content: Record<string, unknown>, name: string): ComponentDef {
    const def: ComponentDef = { type: kind, name, ref: `${kind}/${name}`, content };
    return store.put(space, componentCollection(kind), name, def);
}

/** Delete a component AND its archived versions (mirrors the backend's delete-purges-history). */
export function deleteComponent(store: MockStore, space: string, kind: string, id: string): void {
    store.delete(space, componentCollection(kind), id);
    const coll = historyCollection(kind);
    for (const v of store.list<StoredVersion>(space, coll).filter((v) => v.id === id)) {
        store.delete(space, coll, `${id}~v${v.version}`);
    }
}

/** Snapshot the prior content into the kind's history collection, then prune to {@link HISTORY_KEEP}. */
function archiveVersion(store: MockStore, space: string, kind: string, id: string, content: Record<string, unknown>): void {
    const coll = historyCollection(kind);
    const mine = store.list<StoredVersion>(space, coll).filter((v) => v.id === id);
    const next = mine.reduce((mx, v) => Math.max(mx, v.version), 0) + 1;
    store.put(space, coll, `${id}~v${next}`, {
        id, version: next, savedAt: new Date().toISOString(), contentHash: `mock-${id}-v${next}`, content,
    });
    const kept = [...mine.map((v) => v.version), next].sort((a, b) => b - a);
    for (const v of kept.slice(HISTORY_KEEP)) store.delete(space, coll, `${id}~v${v}`);
}

/** Prior copies of a component, newest first (MET-5). */
function listVersions(store: MockStore, space: string, kind: string, id: string): ComponentVersion[] {
    return store.list<StoredVersion>(space, historyCollection(kind))
        .filter((v) => v.id === id)
        .sort((a, b) => b.version - a.version)
        .map((v) => ({ type: kind, id, version: v.version, savedAt: v.savedAt, contentHash: v.contentHash, content: v.content }));
}

/** Restore an archived version as current (which archives the outgoing copy); mirrors the backend. */
function restoreVersion(store: MockStore, space: string, kind: string, id: string, versionStr: string) {
    const version = Number(versionStr);
    if (!Number.isInteger(version)) return error(400, `version must be an integer, got '${versionStr}'`);
    if (!store.get<ComponentDef>(space, componentCollection(kind), id)) return error(404, `no ${kind} '${id}'`);
    const v = store.get<StoredVersion>(space, historyCollection(kind), `${id}~v${version}`);
    if (!v) return error(404, `no version ${version} of ${kind} '${id}'`);
    const restored = putComponent(store, space, kind, { ...v.content, id }, id);
    emitAudit(store, space, {
        action: `${kind}.restored`, category: 'config', targetType: kind, targetId: id,
        message: `Restored ${kind} ${id} to version ${version}`,
    });
    return json(restored);
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
