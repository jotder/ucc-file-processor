import type { ComponentDef } from '../api/components.service';
import type { AuthoredPipeline } from '../api/pipelines.service';
import { componentCollection } from './handlers/components.handler';
import { PIPELINES_COLL } from './handlers/pipelines.handler';
import { MockStore } from './mock-store';

/**
 * Referential-integrity rules for the mock store — the mock analogue of the backend's 409-on-delete.
 * Two conventions are load-bearing today; panes add their own rules as their review (R6) wires them:
 *
 * 1. An authored pipeline node's `use: '<kind>/<id>'` binding references a registry component.
 * 2. A component-model composite's `content.parts[].ref {kind, id}` references its child components
 *    (dashboards → widgets, widgets → inline/named datasets, …).
 */
const COMPONENT_KINDS = ['grammar', 'schema', 'transform', 'sink', 'dataset', 'widget', 'dashboard'];

export function registerIntegrityRules(store: MockStore): void {
    store.addRefRule({
        from: PIPELINES_COLL,
        refs: (e) => {
            const p = e as AuthoredPipeline;
            return (p.nodes ?? []).flatMap((n) => {
                if (!n.use) return [];
                const slash = n.use.indexOf('/');
                if (slash <= 0) return [];
                const kind = n.use.slice(0, slash);
                const id = n.use.slice(slash + 1);
                return COMPONENT_KINDS.includes(kind) && id ? [{ collection: componentCollection(kind), id }] : [];
            });
        },
    });

    for (const kind of COMPONENT_KINDS) {
        store.addRefRule({
            from: componentCollection(kind),
            refs: (e) => {
                const content = (e as ComponentDef).content as
                    | { parts?: Array<{ ref?: { kind?: string; id?: string } }> }
                    | undefined;
                return (content?.parts ?? []).flatMap((p) =>
                    p.ref?.kind && p.ref.id ? [{ collection: componentCollection(p.ref.kind), id: p.ref.id }] : [],
                );
            },
        });
    }
}
