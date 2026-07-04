import { G6Edge, G6GraphData, G6Node, EntityProjection, GraphSource, GraphSourceQuery } from 'app/inspecto/graph';
import { evaluateRows, inferColumns } from 'app/inspecto/query';
import { firstValueFrom } from 'rxjs';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { SAMPLE_SOURCES } from 'app/modules/admin/studio/datasets/dataset-sources';

/**
 * The P3 **entity-projection** GraphSource (GLOSSARY §11): fold a Dataset's rows into a business
 * Entity/Link graph. Distinct source/target column values become **Entities**; each row becomes a
 * **Link**, deduplicated per (source, target, kind) with a folded `count`. Client-side over the same
 * offline row seam the Dataset editor uses (`evaluateRows` over `SAMPLE_SOURCES`) — the real backend
 * projection is open P3 backend work (docs/superpower/link-analysis-and-graphsource.md §7).
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

/** Resolve a Dataset's offline rows exactly as the editor preview does. */
export function datasetRows(ds: Dataset): Record<string, unknown>[] {
    const raw = SAMPLE_SOURCES[ds.sourceName] ?? [];
    if (!ds.query) return raw;
    return evaluateRows(ds.query, { name: ds.sourceName, rows: raw, columns: inferColumns(raw) });
}

/** The pluggable source: Dataset (by `projection.datasetId`) → rows → {@link projectEntities}. */
export class EntityProjectionGraphSource implements GraphSource {
    readonly id = 'entity-projection' as const;
    readonly label = 'Entity/Link (from a Dataset)';
    constructor(private datasets: DatasetsService) {}

    async query(q: GraphSourceQuery): Promise<ProjectedGraph> {
        if (!q.projection?.datasetId) throw new Error('The entity-projection source needs a Dataset mapping.');
        const ds = await firstValueFrom(this.datasets.get(q.projection.datasetId));
        const out = projectEntities(datasetRows(ds), q.projection);
        if (isProjectionError(out)) throw new Error(out.error);
        return out;
    }
}
