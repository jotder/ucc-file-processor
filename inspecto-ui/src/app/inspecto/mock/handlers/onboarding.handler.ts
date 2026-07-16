import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * Stream-onboarding mock domain — the offline mirror of the server-side draft lifecycle
 * (`ConfigRoutes` + pipeline registration, v5.1.0/v5.2.0): write → register → read back →
 * overwrite → delete, plus the stateless `POST /config/preview/parsing` / `.../schema` sample
 * previews (tiny JS parsers that mimic the DuckDB frontends' behaviour: header skip,
 * min-record-length drop, named regex groups, NDJSON validity filter, TRY_CAST-alike type
 * checks). Pipeline + schema + enrichment configs (enrichment registration mirrors pipelines:
 * `POST /enrichment` flips the stored flag); `/config/spec` + `/validate` stay with the demo
 * handler (registered ahead of this one).
 */

export const PIPELINE_CONFIGS_COLL = 'pipeline-config';
export const SCHEMA_CONFIGS_COLL = 'schema-config';
export const ENRICHMENT_CONFIGS_COLL = 'enrichment-config';

/** One stored draft/config file: the decoded map + its write-root-relative path. */
export interface StoredPipelineConfig {
    id: string;
    path: string;
    config: Record<string, unknown>;
    registered: boolean;
}

/** One stored schema file — no registration/active concept (never a pipeline itself). */
export interface StoredSchemaConfig {
    id: string;
    path: string;
    config: Record<string, unknown>;
}

/** One stored enrichment file — registration mirrors pipelines (POST /enrichment hot-registers). */
export interface StoredEnrichmentConfig {
    id: string;
    path: string;
    config: Record<string, unknown>;
    registered: boolean;
}

const WRITE = /\/config\/write$/;
const PREVIEW_PARSING = /\/config\/preview\/parsing$/;
const PREVIEW_SCHEMA = /\/config\/preview\/schema$/;
const CONFIG_FILE = /\/config\/(pipeline|schema|enrichment)\/([^/?]+)$/;
const RUNS = /\/runs$/;
const ENRICHMENT = /\/enrichment$/;

function collFor(type: string): string {
    return type === 'schema' ? SCHEMA_CONFIGS_COLL
        : type === 'enrichment' ? ENRICHMENT_CONFIGS_COLL
        : PIPELINE_CONFIGS_COLL;
}

export function onboardingHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockDemo) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'POST' && WRITE.test(url)) {
            const body = (req.body ?? {}) as { type?: string; config?: Record<string, unknown>; overwrite?: boolean };
            if (!body.config) return undefined;
            if (body.type === 'pipeline') {
                const name = String(body.config['name'] ?? '').trim();
                if (!name) return error(422, "config is missing its identity field 'name'");
                const existing = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, name);
                if (existing && !body.overwrite) return error(409, `file exists: ${name}.toon (pass overwrite:true to replace)`);
                // The real route writes `<name>_pipeline.toon` — the bootstrap-scan convention.
                const path = name.endsWith('_pipeline') ? `${name}.toon` : `${name}_pipeline.toon`;
                store.put(space, PIPELINE_CONFIGS_COLL, name, {
                    id: name,
                    path,
                    config: body.config,
                    registered: existing?.registered ?? false,
                } satisfies StoredPipelineConfig);
                return json({
                    type: 'pipeline', written: true, path, name,
                    bytes: JSON.stringify(body.config).length, overwritten: !!existing, findings: [],
                });
            }
            if (body.type === 'schema') {
                const raw = (body.config['raw'] ?? {}) as Record<string, unknown>;
                const name = String(raw['name'] ?? '').trim();
                if (!name) return error(422, "config is missing its identity field 'raw.name'");
                const existing = store.get<StoredSchemaConfig>(space, SCHEMA_CONFIGS_COLL, name);
                store.put(space, SCHEMA_CONFIGS_COLL, name, {
                    id: name, path: `${name}.toon`, config: body.config,
                } satisfies StoredSchemaConfig);
                return json({
                    type: 'schema', written: true, path: `${name}.toon`, name,
                    bytes: JSON.stringify(body.config).length, overwritten: !!existing, findings: [],
                });
            }
            if (body.type === 'enrichment') {
                const name = String(body.config['name'] ?? '').trim();
                if (!name) return error(422, "config is missing its identity field 'name'");
                const existing = store.get<StoredEnrichmentConfig>(space, ENRICHMENT_CONFIGS_COLL, name);
                if (existing && !body.overwrite) return error(409, `file exists: ${name}.toon (pass overwrite:true to replace)`);
                // The real route writes `<name>_enrich.toon` — the bootstrap-scan convention.
                const path = name.endsWith('_enrich') ? `${name}.toon` : `${name}_enrich.toon`;
                store.put(space, ENRICHMENT_CONFIGS_COLL, name, {
                    id: name,
                    path,
                    config: body.config,
                    registered: existing?.registered ?? false,
                } satisfies StoredEnrichmentConfig);
                return json({
                    type: 'enrichment', written: true, path, name,
                    bytes: JSON.stringify(body.config).length, overwritten: !!existing, findings: [],
                });
            }
            return undefined; // other types: not mocked here
        }

        if (method === 'POST' && PREVIEW_PARSING.test(url)) {
            const body = (req.body ?? {}) as { config?: Record<string, unknown>; sample_text?: string };
            if (!body.config || !String(body.sample_text ?? '').trim()) {
                return error(400, "body must include 'config' (a pipeline draft map) and 'sample_text'");
            }
            try {
                return json(previewParsing(body.config, String(body.sample_text)));
            } catch (e) {
                return error(422, e instanceof Error ? e.message : 'sample does not parse with these settings');
            }
        }

        if (method === 'POST' && PREVIEW_SCHEMA.test(url)) {
            const body = (req.body ?? {}) as { config?: Record<string, unknown>; sampleRows?: Record<string, unknown>[] };
            if (!body.config || !body.sampleRows?.length) {
                return error(400, "body must include 'config' (a schema draft map) and non-empty 'sampleRows'");
            }
            try {
                return json(previewSchema(body.config, body.sampleRows));
            } catch (e) {
                return error(422, e instanceof Error ? e.message : 'schema preview failed');
            }
        }

        if (method === 'GET' && (m = match(url, CONFIG_FILE))) {
            const [, type, name] = m;
            const rec = store.get<StoredPipelineConfig | StoredSchemaConfig>(space, collFor(type), name);
            if (!rec) return error(404, `no such config: ${name}.toon`);
            return json({ type, name: rec.id, path: rec.path, config: rec.config });
        }

        if (method === 'DELETE' && (m = match(url, CONFIG_FILE))) {
            const [, type, name] = m;
            const rec = store.get<StoredPipelineConfig | StoredSchemaConfig>(space, collFor(type), name);
            if (!rec) return error(404, `no such config: ${name}.toon`);
            if (type === 'pipeline' && (rec as StoredPipelineConfig).config['active'] === true) {
                return error(409, `pipeline '${rec.id}' is active; deactivate (active: false) before deleting`);
            }
            store.delete(space, collFor(type), rec.id);
            return json({ type, name: rec.id, deleted: true, path: rec.path });
        }

        if (method === 'POST' && RUNS.test(url)) {
            const configPath = String((req.body as { configPath?: string } | null)?.configPath ?? '').trim();
            if (!configPath) return error(400, "body must include 'configPath'");
            const rec = store
                .list<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL)
                .find((r) => r.path === configPath || `${r.id}.toon` === configPath);
            if (!rec) return error(404, `no config file at ${configPath}`);
            store.put(space, PIPELINE_CONFIGS_COLL, rec.id, { ...rec, registered: true });
            return json({ registered: true, id: rec.id, path: rec.path, findings: [] });
        }

        if (method === 'POST' && ENRICHMENT.test(url)) {
            const configPath = String((req.body as { configPath?: string } | null)?.configPath ?? '').trim();
            if (!configPath) return error(400, "body must include 'configPath'");
            const rec = store
                .list<StoredEnrichmentConfig>(space, ENRICHMENT_CONFIGS_COLL)
                .find((r) => r.path === configPath || `${r.id}.toon` === configPath);
            if (!rec) return error(404, `no config file at ${configPath}`);
            store.put(space, ENRICHMENT_CONFIGS_COLL, rec.id, { ...rec, registered: true });
            return json({ registered: true, name: rec.id, path: rec.path, findings: [] });
        }

        return undefined;
    };
}

/** Catalog projections of the registered drafts — merged into the demo streams/references lists. */
export function draftStreamRows(store: MockStore, space: string): Record<string, unknown>[] {
    return registered(store, space)
        .filter((r) => String(r.config['produces'] ?? '') !== 'reference')
        .map((r) => originRow(r, 'STREAM', `stream:${r.id}`));
}

export function draftReferenceRows(store: MockStore, space: string): Record<string, unknown>[] {
    return registered(store, space)
        .filter((r) => String(r.config['produces'] ?? '') === 'reference')
        .map((r) => originRow(r, 'REFERENCE_DATASET', `ref:${r.id}`));
}

function registered(store: MockStore, space: string): StoredPipelineConfig[] {
    return store.list<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL).filter((r) => r.registered);
}

function originRow(r: StoredPipelineConfig, kind: string, id: string): Record<string, unknown> {
    const collector = (r.config['collector'] ?? {}) as Record<string, unknown>;
    return {
        id,
        kind,
        label: r.id,
        description: {
            text: String(r.config['description'] ?? `${String(collector['connector'] ?? 'local')} collector feeding ${r.id}`),
            source: 'collector',
        },
        attrs: {
            connector: String(collector['connector'] ?? 'local'),
            connection: (collector['connection'] as string | undefined) ?? null,
            pipeline: r.id,
            active: r.config['active'] === true,
        },
    };
}

// ── the tiny frontend parsers (mirror the real preview's semantics, JS-grade) ──────────────

interface Preview {
    frontend: string;
    columns: string[];
    rowCount: number;
    rows: Record<string, unknown>[];
    rejectedRows: number;
}

interface SchemaPreviewMock {
    columns: string[];
    okCount: number;
    rejectedCount: number;
    rejectedRows: Record<string, unknown>[];
}

/** Mirrors `ComponentPreview.schema()`'s cast set: only DOUBLE/DATE/TIMESTAMP actually reject a
 *  non-blank value that fails; VARCHAR (and anything else) always passes. */
function previewSchema(config: Record<string, unknown>, sampleRows: Record<string, unknown>[]): SchemaPreviewMock {
    const raw = (config['raw'] ?? {}) as Record<string, unknown>;
    const fields = (Array.isArray(raw['fields']) ? raw['fields'] : []) as { name?: string; type?: string }[];
    const columns = [...new Set(sampleRows.flatMap((r) => Object.keys(r)))];
    if (!columns.length) throw new Error('sample rows have no columns');
    if (!fields.length) throw new Error("schema has no typed fields (expected 'raw.fields' / 'fields' / 'columns')");

    const castOk = (value: unknown, type?: string): boolean => {
        const v = value === undefined || value === null ? '' : String(value);
        if (v === '') return true; // blank/null: never rejects
        switch ((type ?? '').trim().toUpperCase()) {
            case 'DOUBLE': return Number.isFinite(Number(v));
            case 'DATE':
            case 'TIMESTAMP': return !Number.isNaN(Date.parse(v));
            default: return true; // VARCHAR / unknown: never rejects
        }
    };

    const ok: Record<string, unknown>[] = [];
    const rejected: Record<string, unknown>[] = [];
    for (const row of sampleRows) {
        const allCast = fields.every((f) => !f.name || !(f.name in row) || castOk(row[f.name], f.type));
        (allCast ? ok : rejected).push(row);
    }
    return { columns, okCount: ok.length, rejectedCount: rejected.length, rejectedRows: rejected.slice(0, 200) };
}

function previewParsing(config: Record<string, unknown>, sampleText: string): Preview {
    const parsing = (config['parsing'] ?? {}) as Record<string, unknown>;
    const delimited = (parsing['delimited'] ?? {}) as Record<string, unknown>;
    const frontend = String(parsing['frontend'] ?? 'delimited').toLowerCase();
    const proc = (config['processing'] ?? {}) as Record<string, unknown>;
    if (frontend === 'plugin' || proc['ingester']) {
        throw new Error('parsing preview is not supported for the plugin frontend — run the pipeline against a real file instead');
    }

    // Engine default: has_header true — except json/text_regex, whose records have no header.
    const headerless = frontend === 'json' || frontend === 'text_regex';
    const hasHeader = delimited['has_header'] === undefined ? !headerless : delimited['has_header'] === true;
    const skip = Number(delimited['skip_header_lines'] ?? 0) + (hasHeader && frontend !== 'delimited' ? 1 : 0);
    const allLines = sampleText.split(/\r?\n/).filter((l, i, a) => l.length > 0 || i < a.length - 1);
    const lines = allLines.slice(skip);

    switch (frontend) {
        case 'fixedwidth':
        case 'fixed_width': {
            const fw = (parsing['fixedwidth'] ?? {}) as Record<string, unknown>;
            const fields = (fw['fields'] ?? []) as { name?: string; start?: number; length?: number }[];
            if (!fields.length) throw new Error('fixedwidth.fields[] must be a non-empty list of {name,start,length}');
            const widest = Math.max(...fields.map((f) => Number(f.start ?? 0) + Number(f.length ?? 1)));
            const minLen = Number(fw['min_record_length'] ?? 0) || widest;
            const columns = fields.map((f, i) => String(f.name ?? `field_${i}`));
            const rows = lines
                .filter((l) => l.length >= minLen)
                .map((l) => Object.fromEntries(fields.map((f, i) => [
                    columns[i],
                    l.substring(Number(f.start ?? 0), Number(f.start ?? 0) + Number(f.length ?? 1)).trim(),
                ])));
            return { frontend: 'fixedwidth', columns, rowCount: rows.length, rows: rows.slice(0, 200), rejectedRows: 0 };
        }
        case 'json': {
            const jf = String(((parsing['json'] ?? {}) as Record<string, unknown>)['format'] ?? 'newline');
            if (jf === 'newline') {
                const parsed: Record<string, unknown>[] = [];
                let invalid = 0;
                for (const l of lines) {
                    if (!l.trim()) continue;
                    try {
                        parsed.push(JSON.parse(l) as Record<string, unknown>);
                    } catch {
                        invalid++;
                    }
                }
                const columns = [...new Set(parsed.flatMap((r) => Object.keys(r)))];
                return { frontend: 'json', columns, rowCount: parsed.length, rows: parsed.slice(0, 200), rejectedRows: invalid };
            }
            const doc = JSON.parse(sampleText) as unknown;
            const arr = Array.isArray(doc) ? (doc as Record<string, unknown>[]) : [doc as Record<string, unknown>];
            const columns = [...new Set(arr.flatMap((r) => Object.keys(r)))];
            return { frontend: 'json', columns, rowCount: arr.length, rows: arr.slice(0, 200), rejectedRows: 0 };
        }
        case 'text_regex': {
            const tr = (parsing['text_regex'] ?? {}) as Record<string, unknown>;
            const raw = String(tr['pattern'] ?? '');
            if (!raw) throw new Error('text_regex.pattern is required');
            const jsPattern = raw.replace(/\(\?P</g, '(?<');
            const re = new RegExp(jsPattern);
            const groups = [...raw.matchAll(/\(\?P?<([A-Za-z][A-Za-z0-9_]*)>/g)].map((g) => g[1]);
            if (!groups.length) throw new Error('text_regex.pattern must contain at least one named capture group');
            const rows = lines
                .map((l) => re.exec(l)?.groups)
                .filter((g): g is Record<string, string> => !!g)
                .map((g) => Object.fromEntries(groups.map((k) => [k, g[k] ?? null])));
            return { frontend: 'text_regex', columns: groups, rowCount: rows.length, rows: rows.slice(0, 200), rejectedRows: 0 };
        }
        default: {
            const delim = String(delimited['delimiter'] ?? ',') || ',';
            const skipDataLines = Number(delimited['skip_header_lines'] ?? 0);
            const body = allLines.slice(skipDataLines);
            const header = hasHeader ? body[0] : null;
            const dataLines = hasHeader ? body.slice(1) : body;
            const width = (header ?? dataLines[0] ?? '').split(delim).length;
            const columns = header ? header.split(delim).map((h) => h.trim()) : Array.from({ length: width }, (_, i) => `column${i}`);
            let rejected = 0;
            const rows: Record<string, unknown>[] = [];
            for (const l of dataLines) {
                if (!l.length) continue;
                const cells = l.split(delim);
                if (cells.length !== columns.length) {
                    rejected++;
                    continue;
                }
                rows.push(Object.fromEntries(columns.map((c, i) => [c, cells[i]])));
            }
            return { frontend: 'delimited', columns, rowCount: rows.length, rows: rows.slice(0, 200), rejectedRows: rejected };
        }
    }
}
