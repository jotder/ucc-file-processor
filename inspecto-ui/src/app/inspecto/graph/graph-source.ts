import type { GraphDirection } from 'app/inspecto/api';
import type { G6GraphData } from './graph-types';

/**
 * The **GraphSource seam** (GLOSSARY §11, design: docs/superpower/link-analysis-and-graphsource.md):
 * one renderer (`GraphViewComponent`) + one query shape + N pluggable sources. Framework-agnostic —
 * concrete sources live with their feature (they wrap the existing pure mappers and, where needed,
 * an API service); this file is types only, so anything can depend on it without importing a feature.
 */

/** The four graph planes a query can target (GLOSSARY §11: P1 / P2 / P2′ / P3). */
export type GraphSourceId = 'component-registry' | 'lineage' | 'provenance' | 'entity-projection';

/**
 * The `entity-projection` mapping (P3): fold a Dataset's rows into a business Entity/Link graph —
 * distinct source/target column values become Entities, each row a Link. `linkKindCol` types the
 * Link from a column's value; `attrCols` carry extra row attributes onto the Link.
 */
export interface EntityProjection {
    datasetId: string;
    sourceCol: string;
    targetCol: string;
    linkKindCol?: string;
    attrCols?: string[];
}

/**
 * The unified graph query — the generalization of the lineage plane's `GraphQuery`
 * (`GET /catalog/graph`) plus the per-plane extras. A source reads only the fields it understands.
 */
export interface GraphSourceQuery {
    /** Root node; absent = the whole graph. */
    from?: string;
    /** BFS radius from `from`. */
    depth?: number;
    direction?: GraphDirection;
    /** Node-kind filter. */
    kinds?: string[];
    /** Edge-kind filter. */
    edgeKinds?: string[];
    /** P2: attach the operational overlay. */
    overlay?: boolean;
    /** P2′: weight edges by provenance row counts. */
    counts?: boolean;
    /** P3: the Dataset column→Entity/Link mapping (entity-projection only). */
    projection?: EntityProjection;
}

/** One pluggable origin of graph data. `query()` may hit the backend or derive client-side. */
export interface GraphSource {
    readonly id: GraphSourceId;
    readonly label: string;
    query(q: GraphSourceQuery): Promise<G6GraphData>;
}
