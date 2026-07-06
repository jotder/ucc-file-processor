import type { ComponentDef } from '../api/components.service';
import { refsForComponent } from '../component-model';
import { componentCollection } from './handlers/components.handler';
import { CONNECTIONS_COLL } from './handlers/connections.handler';
import { DECISION_RULES_COLL } from './handlers/decision-rules.handler';
import { JOBS_COLL } from './handlers/jobs.handler';
import { PIPELINES_COLL } from './handlers/pipelines.handler';
import { MockStore } from './mock-store';

/**
 * Referential-integrity rules for the mock store — the mock analogue of the backend's 409-on-delete.
 * R1 (living-operational-system.md §5): every rule is the ONE metadata-network derivation
 * (`refsForComponent`) mapped onto store collections — deleting a dataset a widget binds, a view a
 * widget renders, a widget a dashboard tiles, or a connection/grammar a pipeline node binds, all 409
 * with the referencers listed. No per-kind rules to keep in sync anymore.
 */
const COMPONENT_KINDS = [
    'grammar', 'schema', 'transform', 'sink', 'dataset', 'query', 'widget', 'dashboard', 'geo-map-view', 'link-analysis-view',
];

/** Map a derived ref's target kind onto the store collection that holds it. */
function collectionOf(kind: string): string {
    if (kind === 'connection') return CONNECTIONS_COLL;
    if (kind === 'pipeline' || kind === 'authored-pipeline') return PIPELINES_COLL;
    if (kind === 'job') return JOBS_COLL;
    return componentCollection(kind);
}

export function registerIntegrityRules(store: MockStore): void {
    store.addRefRule({
        from: PIPELINES_COLL,
        refs: (e) => refsForComponent('pipeline', e as Record<string, unknown>).map((r) => ({ collection: collectionOf(r.kind), id: r.id })),
    });

    // A job's `triggers` edge protects the pipeline it listens to (R2).
    store.addRefRule({
        from: JOBS_COLL,
        refs: (e) => refsForComponent('job', e as Record<string, unknown>).map((r) => ({ collection: collectionOf(r.kind), id: r.id })),
    });

    // A decision rule's `binds` (target) + `invokes` (platform-consequence target) edges protect the
    // pipeline/job/widget it acts on (R5): deleting an invoked job/pipeline/widget 409s.
    store.addRefRule({
        from: DECISION_RULES_COLL,
        refs: (e) => refsForComponent('decision-rule', e as Record<string, unknown>).map((r) => ({ collection: collectionOf(r.kind), id: r.id })),
    });

    for (const kind of COMPONENT_KINDS) {
        store.addRefRule({
            from: componentCollection(kind),
            refs: (e) =>
                refsForComponent(kind, ((e as ComponentDef).content ?? {}) as Record<string, unknown>).map((r) => ({
                    collection: collectionOf(r.kind),
                    id: r.id,
                })),
        });
    }
}
