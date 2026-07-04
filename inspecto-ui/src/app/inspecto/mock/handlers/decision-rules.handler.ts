import type { DecisionRule, DecisionSimulation } from '../../api/decision-rules.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The Decision Rules mock domain (C3) — CRUD over business routing rules plus the dry-run
 * simulation. Simulation is a pure preview (matched/total counts) with no side effects — routing is
 * not a failure, so unlike Expectations (C2) nothing raises an Incident.
 *
 * Determinism: there are no real records to route, so a seeded rule may carry mock-only
 * `demoMatched`/`demoTotal` counts; user-authored rules simulate 0 matches over the default window.
 */

export const DECISION_RULES_COLL = 'decision-rule';

/** Mock-only extension: seeded rows can predetermine their simulation outcome. */
export interface MockDecisionRule extends DecisionRule {
    demoMatched?: number;
    demoTotal?: number;
}

const LIST = /\/decision-rules$/;
const ONE = /\/decision-rules\/([^/]+)$/;
const SIMULATE = /\/decision-rules\/([^/]+)\/simulate$/;

export function decisionRulesHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockOps) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'GET' && LIST.test(url)) {
            return json(
                store
                    .list<MockDecisionRule>(space, DECISION_RULES_COLL)
                    .sort((a, b) => a.priority - b.priority || a.name.localeCompare(b.name)),
            );
        }
        if (method === 'POST' && (m = match(url, SIMULATE))) {
            const rule = store.get<MockDecisionRule>(space, DECISION_RULES_COLL, m[1]);
            if (!rule) return error(404, `decision rule ${m[1]} not found`);
            const sim: DecisionSimulation = {
                matched: rule.demoMatched ?? 0,
                total: rule.demoTotal ?? 1000,
                checkedAt: Date.now(),
            };
            const next: MockDecisionRule = { ...rule, lastSimulation: sim, updatedAt: sim.checkedAt };
            return json(store.put(space, DECISION_RULES_COLL, next.name, next));
        }
        if (method === 'POST' && LIST.test(url)) {
            const b = (req.body ?? {}) as Partial<MockDecisionRule>;
            if (!b.name) return error(422, 'name is required');
            if (!b.consequences?.length) return error(422, 'at least one consequence is required');
            if (store.get(space, DECISION_RULES_COLL, b.name)) return error(409, `decision rule "${b.name}" already exists`);
            const now = Date.now();
            const rule: MockDecisionRule = { ...normalize(b), name: b.name, lastSimulation: null, createdAt: now, updatedAt: now };
            return json(store.put(space, DECISION_RULES_COLL, rule.name, rule));
        }
        if (method === 'PUT' && (m = match(url, ONE))) {
            const prev = store.get<MockDecisionRule>(space, DECISION_RULES_COLL, m[1]);
            if (!prev) return error(404, `decision rule ${m[1]} not found`);
            const b = (req.body ?? {}) as Partial<MockDecisionRule>;
            if (b.consequences && !b.consequences.length) return error(422, 'at least one consequence is required');
            const rule: MockDecisionRule = { ...prev, ...normalize(b), name: prev.name, updatedAt: Date.now() };
            return json(store.put(space, DECISION_RULES_COLL, rule.name, rule));
        }
        if (method === 'DELETE' && (m = match(url, ONE))) {
            if (!store.get(space, DECISION_RULES_COLL, m[1])) return error(404, `decision rule ${m[1]} not found`);
            store.delete(space, DECISION_RULES_COLL, m[1]);
            return json({ deleted: m[1] });
        }

        return undefined;
    };
}

/** Clamp an upsert body to the model's own fields. */
function normalize(b: Partial<MockDecisionRule>): Omit<MockDecisionRule, 'name' | 'lastSimulation' | 'createdAt' | 'updatedAt'> {
    return {
        description: b.description ?? '',
        targetType: b.targetType === 'job' ? 'job' : 'pipeline',
        target: String(b.target ?? ''),
        when: b.when ?? { kind: 'group', op: 'AND', items: [] },
        consequences: (b.consequences ?? []).map((c) => ({
            action: c.action,
            destination: c.destination ?? null,
        })),
        priority: Number.isFinite(b.priority) ? Number(b.priority) : 100,
        enabled: b.enabled !== false,
        ...(b.demoMatched !== undefined ? { demoMatched: b.demoMatched } : {}),
        ...(b.demoTotal !== undefined ? { demoTotal: b.demoTotal } : {}),
    };
}
