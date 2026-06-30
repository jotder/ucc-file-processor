import { ColumnMeta, ColumnType, QueryModel } from 'app/inspecto/query';

/**
 * Studio **Dataset** model — a data-source abstraction (not merely "a saved query"), the first Studio kind
 * built on the unified component metamodel. A dataset is stored as a `dataset` component (mock-served today);
 * its component `config` is the {@link DatasetConfig} below (everything except the id/name, which live on the
 * Component itself). Mirrors the rule-builder's `rule-types.ts` — pure data, no Angular.
 *
 * - **physical**: a concrete table / parquet / catalog ref (`physicalRef`).
 * - **virtual**: a SQL view over a source — embeds the Query Core {@link QueryModel} (projection + filter).
 * - **materialized**: a virtual dataset whose result is persisted (later phase; modelled now for completeness).
 */
export type DatasetKind = 'physical' | 'virtual' | 'materialized';

/** A column's analytic role — what a chart may bind it to (Tableau-style measure/dimension/time split). */
export type DatasetRole = 'dimension' | 'measure' | 'temporal';

/** One typed, role-tagged column with an optional display label + format. */
export interface DatasetColumn {
    name: string;
    type: ColumnType;
    role: DatasetRole;
    label?: string;
    /** A format key resolved by Studio's formatter later (e.g. `int`, `bytes`, `percent`); free-form for now. */
    format?: string;
    hidden?: boolean;
}

/** A named, reusable aggregate expression (e.g. `sum(duration_s)`) a chart can pick as a measure. */
export interface NamedMeasure {
    id: string;
    label: string;
    expression: string;
    format?: string;
}

/** Default visualization hints carried by the dataset (the chart builder seeds from these). */
export interface DatasetViz {
    defaultType?: string;
    defaultMappings?: Record<string, string>;
}

/** The persisted body of a `dataset` component (the kind-specific `config` in the component model). */
export interface DatasetConfig {
    kind: DatasetKind;
    /** Logical source table the dataset reads from (the Query Core `FROM`). */
    sourceName: string;
    /** Virtual datasets embed the Query Core model (projection + nested AND/OR filter). */
    query?: QueryModel | null;
    /** Physical/materialized datasets point at a catalog table / parquet path / cache id. */
    physicalRef?: string | null;
    columns: DatasetColumn[];
    measures: NamedMeasure[];
    viz?: DatasetViz | null;
}

/** A full dataset = identity + its {@link DatasetConfig}. */
export interface Dataset extends DatasetConfig {
    id: string;
    name: string;
}

/** True for identifier-ish columns (`id`, `*_id`) — excluded from measure inference (you don't sum an id). */
function isIdColumn(name: string): boolean {
    return /(^|_)id$/i.test(name);
}

/**
 * Seed each column's analytic role from its inferred type: temporal for dates, measure for non-id numerics,
 * dimension otherwise. The Studio columns tagger lets the user override these.
 */
export function inferRoles(columns: ColumnMeta[]): DatasetColumn[] {
    return columns.map((c) => ({
        name: c.name,
        type: c.type,
        role: roleFor(c),
    }));
}

function roleFor(c: ColumnMeta): DatasetRole {
    if (c.type === 'date') return 'temporal';
    if (c.type === 'number' && !isIdColumn(c.name)) return 'measure';
    return 'dimension';
}

/** Build a {@link Dataset} from a name/kind/source + optional body (mirrors `buildRuleTemplate`). */
export function buildDataset(
    name: string,
    kind: DatasetKind,
    sourceName: string,
    body?: Partial<DatasetConfig>,
): Dataset {
    return {
        id: name,
        name,
        kind,
        sourceName,
        query: body?.query ?? null,
        physicalRef: body?.physicalRef ?? null,
        columns: body?.columns ?? [],
        measures: body?.measures ?? [],
        viz: body?.viz ?? null,
    };
}
