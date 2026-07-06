import { describe, expect, it } from 'vitest';
import type { DecisionRule } from '../api/decision-rules.service';
import type { Consequence } from '../decision/consequence';
import type { Signal } from '../signal/signal';
import { executeConsequences } from './decision';
import { MockStore } from './mock-store';
import { SIGNALS_COLL } from './signals';

function rule(consequences: Consequence[]): DecisionRule {
    return {
        name: 'r1', targetType: 'pipeline', target: 'cdr_ingest',
        when: { kind: 'group', op: 'AND', items: [] }, consequences, priority: 100, enabled: true,
        createdAt: 0, updatedAt: 0,
    };
}

describe('executeConsequences', () => {
    it('emit-signal writes a signal to the ledger, sourced from the rule', () => {
        const store = new MockStore();
        const out = executeConsequences(store, 'default', rule([
            { action: 'emit-signal', params: { type: 'REVIEW', severity: 'warn', message: 'hi' } },
        ]));
        expect(out[0]).toMatchObject({ action: 'emit-signal', status: 'executed' });
        const sig = store.list<Signal>('default', SIGNALS_COLL).find((s) => s.type === 'REVIEW');
        expect(sig?.source).toEqual({ kind: 'decision-rule', id: 'r1', rel: 'emits' });
        expect(sig?.severity).toBe('warn');
    });

    it('create-alert emits an ALERT_FIRED record signal (carries a rule payload)', () => {
        const store = new MockStore();
        executeConsequences(store, 'default', rule([{ action: 'create-alert', params: { rule: 'ai_x', metric: 'cost' } }]));
        const alert = store.list<Signal>('default', SIGNALS_COLL).find((s) => s.type === 'ALERT_FIRED');
        expect(alert?.payload['rule']).toBe('ai_x');
    });

    it('start-job with a target emits JOB_STARTED; without a target it is skipped', () => {
        const store = new MockStore();
        const out = executeConsequences(store, 'default', rule([
            { action: 'start-job', target: { kind: 'job', id: 'j1' } },
            { action: 'start-job' },
        ]));
        expect(out[0].status).toBe('executed');
        expect(out[1].status).toBe('skipped');
        expect(store.list<Signal>('default', SIGNALS_COLL).some((s) => s.type === 'JOB_STARTED')).toBe(true);
    });

    it('routing actions are record-level — skipped here, no signal written', () => {
        const store = new MockStore();
        const out = executeConsequences(store, 'default', rule([{ action: 'route', destination: 'emea' }, { action: 'drop' }]));
        expect(out.every((o) => o.status === 'skipped')).toBe(true);
        expect(store.list('default', SIGNALS_COLL).length).toBe(0);
    });
});
