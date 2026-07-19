import { G6Edge, G6GraphData, G6Node, EntityProjection, GraphSource, GraphSourceQuery, mergeGraphs } from 'app/inspecto/graph';
import { InvService, ProjectionTriple } from 'app/inspecto/api';
import { evaluateRows, inferColumns } from 'app/inspecto/query';
import { firstValueFrom } from 'rxjs';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { SAMPLE_SOURCES } from 'app/modules/admin/studio/datasets/dataset-sources';

/**
 * The P3 **entity-projection** GraphSource (GLOSSARY §11): fold a Dataset's rows into a business
 * Entity/Link graph. Distinct source/target column values become **Entities**; each row becomes a
 * **Link**, deduplicated per (source, target, kind) with a folded `count`.
 *
 * Since INV-1 (2026-07-08) the projection is <b>backend-first</b>: `POST /inv/projection` does the
 * DuckDB-side aggregation over the real Dataset (scaling far beyond browser row-folding) and
 * {@link projectTriples} maps the aggregated triples into the identical G6 shapes. When the backend
 * is unavailable (offline demo — the mock answers 501) it falls back to the original client-side
 * fold over the Dataset editor's sample rows, byte-identical to the mock-first behaviour.
 */

/** Above this many entities the projection truncates (and says so) rather than melt the canvas. */
export const PROJECTION_NODE_CAP = 500;

/**
 * The Entity node id for a projected value. Type-scoped (`entity:<entityType>:<value>`) when a
 * multi-mapping merge supplies an `entityType`, so a `person` "Bob" stays distinct from an `account`
 * "Bob" (Phase C). Unscoped `entity:<value>` (unchanged since 2026-07-08) otherwise — the single-mapping
 * path never sets `entityType`, so its ids and every existing saved view/export stay byte-identical.
 */
function entityId(entityType: string | undefined, value: string): string {
    return entityType ? `entity:${entityType}:${value}` : `entity:${value}`;
}

/**
 * Merge several {@link ProjectedGraph}s (one per {@link EntityProjection} mapping) into one graph:
 * nodes dedup by id (first mapping wins the node's display data), edges concatenate as-is (their ids
 * already carry source/target/kind/attrs, so a genuine duplicate collapses naturally). `truncated` is
 * true if any input mapping truncated.
 */
export function mergeProjectedGraphs(graphs: ProjectedGraph[]): ProjectedGraph {
    return { ...mergeGraphs(graphs), truncated: graphs.some((g) => g.truncated) };
}

/**
 * Investigation pivot (ui-design-review R8): when a projection column is named for an operational
 * object — `caseId`/`incidentId`/`objectId` (case-insensitive) — the entities it produces ARE record
 * references, so their nodes carry an `objectRef` and the detail dialog offers "Open record".
 */
export function objectRefForColumn(column: string | undefined, value: string): G6Node['data']['objectRef'] {
    const col = (column ?? '').toLowerCase();
    if (col === 'caseid') return { id: value, type: 'CASE' };
    if (col === 'incidentid' || col === 'objectid') return { id: value, type: 'INCIDENT' };
    return undefined;
}

/** A projection failure the UI can render inline (bad mapping ≠ a thrown stack). */
export interface ProjectionError {
    error: string;
}

export interface ProjectedGraph extends G6GraphData {
    /** True when the node cap cut the projection short — surfaced as a banner. */
    truncated: boolean;
}

export function isProjectionError(v: ProjectedGraph | ProjectionError): v is ProjectionError {
    return 'error' in v;
}

/** Pure row→graph fold. Rows with a blank source or target value are skipped. */
export function projectEntities(rows: Record<string, unknown>[], p: EntityProjection): ProjectedGraph | ProjectionError {
    if (!p.sourceCol || !p.targetCol) return { error: 'The mapping needs a source and a target column.' };
    if (rows.length && !(p.sourceCol in rows[0])) return { error: `Column '${p.sourceCol}' is not in the dataset.` };
    if (rows.length && !(p.targetCol in rows[0])) return { error: `Column '${p.targetCol}' is not in the dataset.` };

    const nodes = new Map<string, G6Node>();
    const edges = new Map<string, G6Edge & { data: { kind: string; count: number; attrs?: Record<string, string | null> } }>();
    let truncated = false;

    const ensure = (value: string, column: string): string | null => {
        const id = entityId(p.entityType, value);
        if (!nodes.has(id)) {
            if (nodes.size >= PROJECTION_NODE_CAP) {
                truncated = true;
                return null;
            }
            nodes.set(id, { id, data: { label: value, kind: 'entity', objectRef: objectRefForColumn(column, value) } });
        }
        return id;
    };

    for (const row of rows) {
        const s = String(row[p.sourceCol] ?? '').trim();
        const t = String(row[p.targetCol] ?? '').trim();
        if (!s || !t) continue;
        const sid = ensure(s, p.sourceCol);
        const tid = ensure(t, p.targetCol);
        if (!sid || !tid) continue;
        const kind = p.linkKindCol ? String(row[p.linkKindCol] ?? 'link') : 'link';
        const attrs = p.attrCols?.length
            ? Object.fromEntries(p.attrCols.map((c) => [c, row[c] == null ? null : String(row[c])]))
            : undefined;
        // attrs join the fold key — differing values split a folded pair into separate edges,
        // mirroring the backend's GROUP BY semantics (docs/BACKLOG.md INV-1).
        const key = `${sid}->${tid}:${kind}${attrs ? ':' + JSON.stringify(attrs) : ''}`;
        const existing = edges.get(key);
        if (existing) {
            existing.data.count++;
            existing.data.kind = `${kind} · ${existing.data.count}`;
        } else {
            edges.set(key, { id: key, source: sid, target: tid, data: { kind, count: 1, attrs } });
        }
    }
    return { nodes: [...nodes.values()], edges: [...edges.values()], truncated };
}

/**
 * Fold the backend's aggregated triples (heaviest first) into the same G6 shapes as
 * {@link projectEntities}: `entity:<value>` node ids, `sid->tid:kind` edge ids, `kind · count`
 * folded-edge labels, and the {@link PROJECTION_NODE_CAP} with a truncation flag.
 */
export function projectTriples(triples: ProjectionTriple[], serverTruncated: boolean, p?: EntityProjection): ProjectedGraph {
    const nodes = new Map<string, G6Node>();
    const edges: G6Edge[] = [];
    let truncated = serverTruncated;

    const ensure = (value: string, column?: string): string | null => {
        const id = entityId(p?.entityType, value);
        if (!nodes.has(id)) {
            if (nodes.size >= PROJECTION_NODE_CAP) {
                truncated = true;
                return null;
            }
            nodes.set(id, { id, data: { label: value, kind: 'entity', objectRef: objectRefForColumn(column, value) } });
        }
        return id;
    };

    for (const t of triples) {
        const s = String(t.source ?? '').trim();
        const tv = String(t.target ?? '').trim();
        if (!s || !tv) continue;
        const sid = ensure(s, p?.sourceCol);
        const tid = ensure(tv, p?.targetCol);
        if (!sid || !tid) continue;
        const kind = t.kind ?? 'link';
        edges.push({
            id: `${sid}->${tid}:${kind}${t.attrs ? ':' + JSON.stringify(t.attrs) : ''}`,
            source: sid,
            target: tid,
            data: { kind: t.count > 1 ? `${kind} · ${t.count}` : kind, attrs: t.attrs },
        });
    }
    return { nodes: [...nodes.values()], edges, truncated };
}

/** Resolve a Dataset's offline rows exactly as the editor preview does. */
export function datasetRows(ds: Dataset): Record<string, unknown>[] {
    const raw = SAMPLE_SOURCES[ds.sourceName] ?? [];
    if (!ds.query) return raw;
    return evaluateRows(ds.query, { name: ds.sourceName, rows: raw, columns: inferColumns(raw) });
}

/**
 * The pluggable source: backend projection first ({@code POST /inv/projection} → {@link projectTriples});
 * on any failure (offline demo, pre-INV-1 backend) the original client fold over sample rows.
 */
export class EntityProjectionGraphSource implements GraphSource {
    readonly id = 'entity-projection' as const;
    readonly label = 'Entity/Link (from a Dataset)';
    constructor(
        private datasets: DatasetsService,
        private inv: InvService,
    ) {}

    async query(q: GraphSourceQuery): Promise<ProjectedGraph> {
        if (q.projections?.length) {
            const graphs = await Promise.all(q.projections.map((p) => this.queryOne(p)));
            return mergeProjectedGraphs(graphs);
        }
        if (!q.projection) throw new Error('The entity-projection source needs a Dataset mapping.');
        return this.queryOne(q.projection);
    }

    /** One mapping's projection: backend-first, falling back to the client sample fold on failure. */
    private async queryOne(p: EntityProjection): Promise<ProjectedGraph> {
        if (!p.datasetId) throw new Error('The entity-projection source needs a Dataset mapping.');
        if (!p.sourceCol || !p.targetCol) throw new Error('The mapping needs a source and a target column.');
        try {
            const res = await firstValueFrom(this.inv.project({
                dataset: p.datasetId,
                sourceCol: p.sourceCol,
                targetCol: p.targetCol,
                linkKindCol: p.linkKindCol || undefined,
                attrCols: p.attrCols?.length ? p.attrCols : undefined,
            }));
            return projectTriples(res.rows, res.truncated, p);
        } catch {
            // Offline / mock (501) or an older backend: the original client-side sample fold.
            const ds = await firstValueFrom(this.datasets.get(p.datasetId));
            const out = projectEntities(datasetRows(ds), p);
            if (isProjectionError(out)) throw new Error(out.error);
            return out;
        }
    }

    /**
     * Phase E incremental expand: the one-hop neighborhood of `nodeLabel` (the entity's raw projected
     * value) via `POST /inv/projection/neighbors`. Only supported for a single-mapping query — a
     * multi-mapping graph (`q.projections`) doesn't record which mapping produced which node, so
     * expand there needs a follow-up scope decision, not a guess; it throws a clear message instead.
     */
    async expand(_nodeId: string, nodeLabel: string, q: GraphSourceQuery): Promise<ProjectedGraph> {
        const p = q.projection;
        if (!p) throw new Error('Incremental expand needs a single-mapping query.');
        const res = await firstValueFrom(this.inv.neighbors({
            dataset: p.datasetId,
            sourceCol: p.sourceCol,
            targetCol: p.targetCol,
            linkKindCol: p.linkKindCol || undefined,
            attrCols: p.attrCols?.length ? p.attrCols : undefined,
            value: nodeLabel,
        }));
        return projectTriples(res.rows, res.truncated, p);
    }
}
