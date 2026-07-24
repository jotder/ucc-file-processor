import { describe, expect, it } from 'vitest';
import { type Consequence, consequenceRefs, describeConsequence } from './consequence';

describe('consequenceRefs', () => {
    it('emits an `invokes` edge for platform actions that target a component', () => {
        const cs: Consequence[] = [
            { action: 'route', destination: 'emea' },
            { action: 'start-job', target: { kind: 'job', id: 'daily_summary_report' } },
            { action: 'trigger-pipeline', target: { kind: 'pipeline', id: 'cdr_ingest' } },
            { action: 'render-widget', target: { kind: 'widget', id: 'w1' } },
        ];
        expect(consequenceRefs(cs)).toEqual([
            { kind: 'job', id: 'daily_summary_report', rel: 'invokes', via: 'consequence1' },
            { kind: 'pipeline', id: 'cdr_ingest', rel: 'invokes', via: 'consequence2' },
            { kind: 'widget', id: 'w1', rel: 'invokes', via: 'consequence3' },
        ]);
    });

    it('ignores routing actions and platform actions with no component target', () => {
        const cs: Consequence[] = [
            { action: 'tag', destination: 'high_risk' },
            { action: 'quarantine' },
            { action: 'emit-signal', params: { type: 'CUSTOM' } },
            { action: 'create-alert', params: { rule: 'x' } },
            { action: 'start-job' }, // no target → no edge
        ];
        expect(consequenceRefs(cs)).toEqual([]);
    });
});

describe('describeConsequence', () => {
    it('summarises each action', () => {
        expect(describeConsequence({ action: 'route', destination: 'emea' })).toBe('Route to emea');
        expect(describeConsequence({ action: 'quarantine' })).toBe('Quarantine');
        expect(describeConsequence({ action: 'quarantine', destination: 'fraud' })).toBe('Quarantine (fraud)');
        expect(describeConsequence({ action: 'drop' })).toBe('Drop');
        expect(describeConsequence({ action: 'start-job', target: { kind: 'job', id: 'j1' } })).toBe('Start job j1');
        expect(describeConsequence({ action: 'emit-signal', params: { type: 'REVIEW' } })).toBe('Emit signal REVIEW');
        expect(describeConsequence({ action: 'create-incident', params: { title: 'Orders under watch' } })).toBe('Open incident Orders under watch');
    });
});
