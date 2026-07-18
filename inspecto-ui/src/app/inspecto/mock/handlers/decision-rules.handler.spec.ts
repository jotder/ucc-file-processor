import { describe, expect, it } from 'vitest';
import type { DecisionRule } from '../../api/decision-rules.service';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { decisionRulesHandler } from './decision-rules.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

const UPSERT = {
    name: 'route_apac',
    targetType: 'pipeline',
    target: 'cdr_ingest',
    when: { kind: 'group', op: 'AND', items: [{ kind: 'condition', field: 'tariff', operator: 'startsWith', value: 'APAC_' }] },
    consequences: [{ action: 'route', destination: 'apac' }],
    priority: 15,
};

describe('decisionRulesHandler', () => {
    const handler = decisionRulesHandler({ mockOps: true });

    it('lists the seeded rules sorted by priority', () => {
        const store = seededStore();
        const list = handler(req('GET', '/api/decision-rules'), store)?.body as DecisionRule[];
        expect(list.map((r) => r.name)).toEqual(['route_emea_traffic', 'quarantine_high_cost', 'drop_zero_duration']);
    });

    it('round-trips create/update/delete, requires a consequence, and enforces the duplicate 409', () => {
        const store = seededStore();
        const created = handler(req('POST', '/api/decision-rules', UPSERT), store)?.body as DecisionRule;
        expect(created.enabled).toBe(true);
        expect(created.priority).toBe(15);

        expect(handler(req('POST', '/api/decision-rules', UPSERT), store)?.status).toBe(409);
        expect(handler(req('POST', '/api/decision-rules', { ...UPSERT, name: 'x', consequences: [] }), store)?.status).toBe(422);

        const updated = handler(req('PUT', '/api/decision-rules/route_apac', { ...UPSERT, priority: 5 }), store)?.body as DecisionRule;
        expect(updated.priority).toBe(5);

        handler(req('DELETE', '/api/decision-rules/route_apac'), store);
        expect(handler(req('DELETE', '/api/decision-rules/route_apac'), store)?.status).toBe(404);
    });

    it('simulate with no sample falls back to the seeded demo counts, no side effects', () => {
        const store = seededStore();
        const sim = handler(req('POST', '/api/decision-rules/quarantine_high_cost/simulate'), store)?.body as DecisionRule;
        expect(sim.lastSimulation?.matched).toBe(7);
        expect(sim.lastSimulation?.total).toBe(1000);
        // routing is not a failure — no incident may appear
        expect(
            store.list<{ correlationId?: string }>('default', 'ops-object')
                .some((o) => (o.correlationId ?? '').startsWith('decision')),
        ).toBe(false);

        // a user-authored rule (no demo counts) falls back to 0
        handler(req('POST', '/api/decision-rules', UPSERT), store);
        const fresh = handler(req('POST', '/api/decision-rules/route_apac/simulate'), store)?.body as DecisionRule;
        expect(fresh.lastSimulation).toEqual(expect.objectContaining({ matched: 0, total: 0 }));
    });

    it('simulate evaluates the when-clause over a supplied sample (real matched/total)', () => {
        const store = seededStore();
        // quarantine_high_cost: cost_usd > 100 AND duration_s < 60
        const sampleRows = [
            { cost_usd: 250, duration_s: 30 }, // match
            { cost_usd: 250, duration_s: 90 }, // duration too long
            { cost_usd: 50, duration_s: 10 }, // cost too low
            { cost_usd: 999, duration_s: 5 }, // match
        ];
        const sim = handler(
            req('POST', '/api/decision-rules/quarantine_high_cost/simulate', { sampleRows }),
            store,
        )?.body as DecisionRule;
        expect(sim.lastSimulation?.matched).toBe(2);
        expect(sim.lastSimulation?.total).toBe(4);
    });

    it('gates on mockOps', () => {
        const store = seededStore();
        expect(decisionRulesHandler({})(req('GET', '/api/decision-rules'), store)).toBeUndefined();
    });
});
