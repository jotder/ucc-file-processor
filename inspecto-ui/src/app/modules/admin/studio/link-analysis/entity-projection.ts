import { G6Edge, G6GraphData, G6Node, EntityProjection, GraphSource, GraphSourceQuery } from 'app/inspecto/graph';
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
    const edges = new Map<string, G6Edge & { data: { kind: string; count: number } }>();
    let truncated = false;

    const ensure = (value: string): string | null => {
        const id = `entity:${value}`;
        if (!nodes.has(id)) {
            if (nodes.size >= PROJECTION_NODE_CAP) {
                truncated = true;
                return null;
            }
            nodes.set(id, { id, data: { label: value, kind: 'entity' } });
        }
        return id;
    };

    for (const row of rows) {
        const s = String(row[p.sourceCol] ?? '').trim();
        const t = String(row[p.targetCol] ?? '').trim();
        if (!s || !t) continue;
        const sid = ensure(s);
        const tid = ensure(t);
        if (!sid || !tid) continue;
        const kind = p.linkKindCol ? String(row[p.linkKindCol] ?? 'link') : 'link';
        const key = `${sid}->${tid}:${kind}`;
        const existing = edges.get(key);
        if (existing) {
            existing.data.count++;
            existing.data.kind = `${kind} · ${existing.data.count}`;
        } else {
            edges.set(key, { id: key, source: sid, target: tid, data: { kind, count: 1 } });
        }
    }
    return { nodes: [...nodes.values()], edges: [...edges.values()], truncated };
}

/**
 * Fold the backend's aggregated triples (heaviest first) into the same G6 shapes as
 * {@link projectEntities}: `entity:<value>` node ids, `sid->tid:kind` edge ids, `kind · count`
 * folded-edge labels, and the {@link PROJECTION_NODE_CAP} with a truncation flag.
 */
export function projectTriples(triples: ProjectionTriple[], serverTruncated: boolean): ProjectedGraph {
    const nodes = new Map<string, G6Node>();
    const edges: G6Edge[] = [];
    let truncated = serverTruncated;

    const ensure = (value: string): string | null => {
        const id = `entity:${value}`;
        if (!nodes.has(id)) {
            if (nodes.size >= PROJECTION_NODE_CAP) {
                truncated = true;
                return null;
            }
            nodes.set(id, { id, data: { label: value, kind: 'entity' } });
        }
        return id;
    };

    for (const t of triples) {
        const s = String(t.source ?? '').trim();
        const tv = String(t.target ?? '').trim();
        if (!s || !tv) continue;
        const sid = ensure(s);
        const tid = ensure(tv);
        if (!sid || !tid) continue;
        const kind = t.kind ?? 'link';
        edges.push({
            id: `${sid}->${tid}:${kind}`,
            source: sid,
            target: tid,
            data: { kind: t.count > 1 ? `${kind} · ${t.count}` : kind },
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
        const p = q.projection;
        if (!p?.datasetId) throw new Error('The entity-projection source needs a Dataset mapping.');
        if (!p.sourceCol || !p.targetCol) throw new Error('The mapping needs a source and a target column.');
        try {
            const res = await firstValueFrom(this.inv.project({
                dataset: p.datasetId,
                sourceCol: p.sourceCol,
                targetCol: p.targetCol,
                linkKindCol: p.linkKindCol || undefined,
            }));
            return projectTriples(res.rows, res.truncated);
        } catch {
            // Offline / mock (501) or an older backend: the original client-side sample fold.
            const ds = await firstValueFrom(this.datasets.get(p.datasetId));
            const out = projectEntities(datasetRows(ds), p);
            if (isProjectionError(out)) throw new Error(out.error);
            return out;
        }
    }
}
