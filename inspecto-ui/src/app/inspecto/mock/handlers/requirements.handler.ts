import type { ComponentDef } from '../../api/components.service';
import type { Requirement, RequirementKind } from '../../requirement';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { componentCollection } from './components.handler';

/**
 * Requirements intake mock ({@code /requirements*}, UI-6 + SEC-7(c)) — the offline half of the dedicated
 * requirements routes. Requirements persist as `requirement` components (collection
 * `component:requirement`, the same one the space seeds populate), so a switch from the generic component
 * CRUD to these routes leaves seeded requirements visible. The submit → decide → deliver lifecycle mirrors
 * the backend `RequirementRoutes`; the triage capability gate is a backend concern (offline = Personal, so
 * the UI's lens gate is the only ceremony here).
 */

const COLL = componentCollection('requirement');
const KINDS = new Set<RequirementKind>(['kpi', 'report', 'reconciliation', 'rule']);

const LIST = /\/requirements$/;
const DECISION = /\/requirements\/([^/]+)\/decision$/;
const DELIVER = /\/requirements\/([^/]+)\/deliver$/;

export function requirementsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockStudio) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'GET' && LIST.test(url)) {
            return json(
                store.list<ComponentDef>(space, COLL)
                    .map((d) => view(d))
                    .sort((a, b) => (a.submittedAt ?? '').localeCompare(b.submittedAt ?? '')),
            );
        }
        if (method === 'POST' && (m = match(url, DECISION))) {
            const d = store.get<ComponentDef>(space, COLL, m[1]);
            if (!d) return error(404, `requirement ${m[1]} not found`);
            if (d.content['status'] !== 'submitted') return error(409, `requirement ${m[1]} is not awaiting a decision`);
            const b = (req.body ?? {}) as { accept?: boolean; note?: string };
            return json(patch(store, space, d, {
                status: b.accept ? 'accepted' : 'rejected',
                decisionNote: b.note ?? null,
                decidedAt: new Date().toISOString(),
            }));
        }
        if (method === 'POST' && (m = match(url, DELIVER))) {
            const d = store.get<ComponentDef>(space, COLL, m[1]);
            if (!d) return error(404, `requirement ${m[1]} not found`);
            if (d.content['status'] !== 'accepted') return error(409, `only an accepted requirement can be delivered`);
            const b = (req.body ?? {}) as { note?: string };
            return json(patch(store, space, d, { status: 'delivered', deliveredNote: b.note ?? null, deliveredAt: new Date().toISOString() }));
        }
        if (method === 'POST' && LIST.test(url)) {
            const b = (req.body ?? {}) as Partial<Requirement>;
            const id = String(b.id ?? '');
            if (!id) return error(422, 'requirement id is required');
            if (!b.title) return error(422, 'requirement title is required');
            if (!b.kind || !KINDS.has(b.kind)) return error(422, `requirement kind must be one of ${[...KINDS].join(', ')}`);
            if (store.get(space, COLL, id)) return error(409, `requirement "${id}" already exists`);
            const content: Record<string, unknown> = {
                title: b.title,
                kind: b.kind,
                description: b.description ?? '',
                status: 'submitted',
                submittedAt: new Date().toISOString(),
            };
            const def: ComponentDef = { type: 'requirement', name: id, ref: `requirement/${id}`, content };
            store.put(space, COLL, id, def);
            return json(view(def));
        }
        return undefined;
    };
}

/** Flat {@link Requirement} view of a stored `requirement` component (name → id). */
function view(d: ComponentDef): Requirement {
    return { id: d.name, ...(d.content as Omit<Requirement, 'id'>) };
}

function patch(store: MockStore, space: string, d: ComponentDef, changes: Record<string, unknown>): Requirement {
    const next: ComponentDef = { ...d, content: { ...d.content, ...changes } };
    store.put(space, COLL, d.name, next);
    return view(next);
}
