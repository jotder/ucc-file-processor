import { ComponentKind, ConfigFinding, Ref, getKind, queryRefs, registerKind } from 'app/inspecto/component-model';
import { QueryConfig } from './query-types';

/**
 * The `query` {@link ComponentKind} — R3 of the living-operational-system roadmap (§4): the query becomes a
 * first-class, reusable artifact. Atomic (`wiring:'none'`); its config is a {@link QueryConfig} that
 * **binds** a source dataset (its one lineage edge) and declares its `$`-parameters. Authoring = the Query
 * Library editor; exec = the same `query` runner key datasets use (AlaSQL offline / DuckDB later).
 */
export const QUERY_KIND: ComponentKind<QueryConfig> = {
    id: 'query',
    label: 'Query',
    allowedPartKinds: [],
    wiring: 'none',
    config: {
        validate: validateQueryConfig,
        create: () => ({ type: 'sql', datasetId: null, text: '', parameters: [] }),
    },
    deriveRefs: (config: QueryConfig): Ref[] => queryRefs(config as unknown as Record<string, unknown>),
    authoring: { editorKey: 'query' },
    exec: { runnerKey: 'query' },
};

/** Tiny hand-written validator: a source dataset is required, and the body matching the type must be present. */
export function validateQueryConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<QueryConfig>;
    const findings: ConfigFinding[] = [];
    if (!c.datasetId) {
        findings.push({ severity: 'error', path: 'datasetId', message: 'Pick a source dataset.' });
    }
    if (c.type === 'sql' && !c.text?.trim()) {
        findings.push({ severity: 'error', path: 'text', message: 'A SQL query needs some text.' });
    }
    if (c.type === 'structured' && !c.model) {
        findings.push({ severity: 'error', path: 'model', message: 'A structured query needs a query model.' });
    }
    return findings;
}

if (!getKind(QUERY_KIND.id)) {
    registerKind(QUERY_KIND);
}
