import type { Expectation, ExpectationResult } from '../../api/expectations.service';
import type { ComponentDef } from '../../api/components.service';
import type { OperationalObject } from '../../api/objects.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { emitSignal } from '../signals';
import { componentCollection, deleteComponent, putComponent, putComponentQuiet } from './components.handler';
import { OPS_OBJECTS_COLL } from './ops.handler';

/**
 * The Expectations mock domain (C2) — CRUD over data-quality checks plus the "run check" evaluation.
 * A FAILED evaluation raises an INCIDENT (correlated `expectation:<name>`, deduped while one is
 * still open) and fans out an EXPECTATION_FAILED notification — the same consequence chain the real
 * engine drives.
 *
 * Storage (unified 2026-07-10): expectations persist in the generic `component:expectation` collection
 * as `ComponentDef` wrappers — the same store the real backend's `ExpectationRoutes` shares with
 * `ComponentStore` — so the MET-5 version-history routes (`/components/expectation/{id}/versions` +
 * restore, served by `componentsHandler`) work for expectations offline. Config edits archive the
 * outgoing copy; a run-check's `lastResult` stamp is written quietly (not an authoring edit), mirroring
 * the backend's `write(…, archive=false)`.
 *
 * Determinism: evaluation has no real records to scan, so a seeded expectation may carry a
 * mock-only `demoViolations` count (> 0 ⇒ FAILED with that count). User-authored expectations
 * evaluate clean. This keeps demos scriptable and specs exact.
 */

const COLL = componentCollection('expectation');

/** Mock-only extension: seeded rows can predetermine their evaluation outcome. */
export interface MockExpectation extends Expectation {
    demoViolations?: number;
}

/** Seed one expectation as its component wrapper (no archiving — seeding is not an edit). */
export function seedExpectation(store: MockStore, space: string, e: MockExpectation): void {
    putComponentQuiet(store, space, 'expectation', e as unknown as Record<string, unknown>, e.name);
}

const LIST = /\/expectations$/;
const EVAL_ALL = /\/expectations\/evaluate$/;
const ONE = /\/expectations\/([^/]+)$/;
const EVAL_ONE = /\/expectations\/([^/]+)\/evaluate$/;

export function expectationsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockOps) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'GET' && LIST.test(url)) {
            return json(list(store, space).sort((a, b) => a.name.localeCompare(b.name)));
        }
        if (method === 'POST' && EVAL_ALL.test(url)) {
            const out = list(store, space).map((e) => (e.enabled ? evaluate(store, space, e) : e));
            return json(out.sort((a, b) => a.name.localeCompare(b.name)));
        }
        if (method === 'POST' && (m = match(url, EVAL_ONE))) {
            const e = get(store, space, m[1]);
            if (!e) return error(404, `expectation ${m[1]} not found`);
            return json(evaluate(store, space, e));
        }
        if (method === 'POST' && LIST.test(url)) {
            const b = (req.body ?? {}) as Partial<MockExpectation>;
            if (!b.name) return error(422, 'name is required');
            if (get(store, space, b.name)) return error(409, `expectation "${b.name}" already exists`);
            const now = Date.now();
            const e: MockExpectation = { ...normalize(b), name: b.name, lastResult: null, createdAt: now, updatedAt: now };
            return json(putComponent(store, space, 'expectation', e, e.name).content);
        }
        if (method === 'PUT' && (m = match(url, ONE))) {
            const prev = get(store, space, m[1]);
            if (!prev) return error(404, `expectation ${m[1]} not found`);
            const b = (req.body ?? {}) as Partial<MockExpectation>;
            const e: MockExpectation = { ...prev, ...normalize(b), name: prev.name, updatedAt: Date.now() };
            // putComponent archives the outgoing copy — a config edit is exactly what history tracks.
            return json(putComponent(store, space, 'expectation', e, e.name).content);
        }
        if (method === 'DELETE' && (m = match(url, ONE))) {
            const prev = get(store, space, m[1]);
            if (!prev) return error(404, `expectation ${m[1]} not found`);
            deleteComponent(store, space, 'expectation', m[1]);   // purges archived versions too
            return json({ deleted: m[1] });
        }

        return undefined;
    };
}

/** All expectations, unwrapped from their component wrappers. */
function list(store: MockStore, space: string): MockExpectation[] {
    return store.list<ComponentDef>(space, COLL).map((d) => d.content as unknown as MockExpectation);
}

/** One expectation by name, unwrapped, or undefined. */
function get(store: MockStore, space: string, name: string): MockExpectation | undefined {
    const d = store.get<ComponentDef>(space, COLL, name);
    return d ? (d.content as unknown as MockExpectation) : undefined;
}

/** Clamp an upsert body to the model's own fields (drops junk, keeps kind params as sent). */
function normalize(b: Partial<MockExpectation>): Omit<MockExpectation, 'name' | 'lastResult' | 'createdAt' | 'updatedAt'> {
    return {
        description: b.description ?? '',
        targetType: b.targetType === 'job' ? 'job' : 'pipeline',
        target: String(b.target ?? ''),
        column: String(b.column ?? ''),
        kind: b.kind ?? 'non_null',
        min: b.min ?? null,
        max: b.max ?? null,
        pattern: b.pattern ?? null,
        refDataset: b.refDataset ?? null,
        refColumn: b.refColumn ?? null,
        severity: b.severity ?? 'MAJOR',
        enabled: b.enabled !== false,
        ...(b.demoViolations !== undefined ? { demoViolations: b.demoViolations } : {}),
    };
}

/** Run one check: persist the result STAMP (quiet — not an authoring edit, so no version is archived);
 *  on FAILED raise a correlated Incident (deduped while open) + fan out. */
function evaluate(store: MockStore, space: string, e: MockExpectation): MockExpectation {
    const violations = e.demoViolations ?? 0;
    const result: ExpectationResult = {
        status: violations > 0 ? 'FAILED' : 'PASSED',
        violations,
        checkedAt: Date.now(),
    };
    const next: MockExpectation = { ...e, lastResult: result, updatedAt: result.checkedAt };
    putComponentQuiet(store, space, 'expectation', next as unknown as Record<string, unknown>, next.name);

    if (result.status === 'FAILED') {
        const correlationId = `expectation:${e.name}`;
        const open = store
            .list<OperationalObject>(space, OPS_OBJECTS_COLL)
            .some((o) => o.correlationId === correlationId && o.status !== 'CLOSED' && o.status !== 'RESOLVED');
        if (!open) {
            const now = result.checkedAt;
            const title = `Expectation failed: ${e.name}`;
            const description = `${e.kind} check on ${e.targetType} "${e.target}" column "${e.column}" — ${violations} violating record(s).`;
            const obj: OperationalObject = {
                id: 'obj-' + now,
                objectType: 'INCIDENT',
                title,
                description,
                status: 'OPEN',
                severity: e.severity,
                priority: undefined,
                owner: undefined,
                assignee: undefined,
                correlationId,
                attributes: {},
                createdAt: now,
                updatedAt: now,
                closedAt: 0,
            };
            store.put(space, OPS_OBJECTS_COLL, obj.id, obj);
            // Emit to the one signal ledger; emitSignal fans out the notification (EXPECTATION_FAILED is a notify type).
            emitSignal(store, space, {
                signalId: `expfail-${now}-${e.name}`,
                type: 'EXPECTATION_FAILED',
                at: now,
                source: { kind: 'expectation', id: e.name, rel: 'emits' },
                correlationId,
                severity: e.severity?.toUpperCase() === 'CRITICAL' ? 'critical' : 'error',
                payload: { name: e.name, title, description, incidentId: obj.id, violations },
            });
        }
    }
    return next;
}
