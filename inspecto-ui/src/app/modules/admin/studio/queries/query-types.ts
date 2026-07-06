import { ParameterDef, QueryModel } from 'app/inspecto/query';

/**
 * Studio **Query** model — R3 of the living-operational-system roadmap (§4): the query lifted out of the
 * artifacts that embedded it inline (a dataset's `QueryModel`, a widget's controls→QuerySpec) into a
 * first-class, reusable `query` Component, so **one query serves many renderings**. Stored as a `query`
 * component (mock-served); this is its kind-specific `config`. Pure data, no Angular — mirrors `dataset-types.ts`.
 *
 * A query references a **source dataset** (its `binds` lineage edge) and declares its runtime
 * {@link ParameterDef}s (the `$`-namespace resolved before execution).
 */

/** The two query types with a live run path: raw SQL (AlaSQL now / DuckDB later) or the structured Query
 *  Core model (projection + filter). `graph`/`spatial`/etc. keep their own shapes in the geo/link views —
 *  folding them into the query kind is a later slice. */
export type QueryType = 'sql' | 'structured';

/** The persisted body of a `query` component (the kind-specific `config`). */
export interface QueryConfig {
    type: QueryType;
    /** The source dataset the query reads — its `binds` lineage edge. */
    datasetId?: string | null;
    /** The logical `FROM` table (the dataset's `sourceName`); carried so the SQL can be run offline. */
    sourceName?: string;
    /** `type:'sql'` — the SQL text; may contain `$`-parameters resolved at run time. */
    text?: string | null;
    /** `type:'structured'` — the Query Core model (projection + nested AND/OR filter). */
    model?: QueryModel | null;
    /** The runtime parameters the query declares (defaults + types for the `$`-namespace). */
    parameters: ParameterDef[];
}

/** A full query = identity + its {@link QueryConfig}. */
export interface Query extends QueryConfig {
    id: string;
    name: string;
    description?: string;
}

/** Build a {@link Query} from a name + type + source (mirrors `buildDataset`/`buildWidget`). */
export function buildQuery(name: string, type: QueryType, body?: Partial<QueryConfig>): Query {
    return {
        id: name,
        name,
        type,
        datasetId: body?.datasetId ?? null,
        sourceName: body?.sourceName,
        text: body?.text ?? null,
        model: body?.model ?? null,
        parameters: body?.parameters ?? [],
    };
}
