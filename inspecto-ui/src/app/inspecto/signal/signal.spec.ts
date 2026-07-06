import { describe, expect, it } from 'vitest';
import type { EventRow } from '../api/events.service';
import type { FiredAlert } from '../api/alerts.service';
import {
    alertSeverityToSignal,
    alertToSignal,
    eventToSignal,
    isAlertSignal,
    levelToSeverity,
    notifyMeta,
    NOTIFY_TYPES,
    type Signal,
    severityToLevel,
    signalToAlert,
    signalToEvent,
    sourceLabel,
} from './signal';

const baseSignal: Signal = {
    signalId: 's1',
    type: 'BATCH_COMMITTED',
    at: 1_700_000_000_000,
    source: { kind: 'pipeline', id: 'cdr_ingest', rel: 'emits' },
    correlationId: 'corr-1',
    severity: 'info',
    payload: { message: 'BATCH_COMMITTED on cdr_ingest', pipeline: 'cdr_ingest', attributes: { rows: '42' } },
};

describe('severity ladder mapping', () => {
    it('projects severity to the EVENT_LEVELS ladder (critical collapses to ERROR)', () => {
        expect(severityToLevel('info')).toBe('INFO');
        expect(severityToLevel('warn')).toBe('WARN');
        expect(severityToLevel('error')).toBe('ERROR');
        expect(severityToLevel('critical')).toBe('ERROR');
    });

    it('lifts a legacy level back onto the ladder', () => {
        expect(levelToSeverity('TRACE')).toBe('trace');
        expect(levelToSeverity('ERROR')).toBe('error');
        expect(levelToSeverity('WEIRD')).toBe('info'); // unknown → info
    });

    it('lifts a FiredAlert severity onto the ladder', () => {
        expect(alertSeverityToSignal('INFO')).toBe('info');
        expect(alertSeverityToSignal('WARNING')).toBe('warn');
        expect(alertSeverityToSignal('CRITICAL')).toBe('critical');
    });
});

describe('source label', () => {
    it('shows the bare kind for system producers and kind/id otherwise', () => {
        expect(sourceLabel({ kind: 'engine', id: 'engine', rel: 'emits' })).toBe('engine');
        expect(sourceLabel({ kind: 'pipeline', id: 'cdr_ingest', rel: 'emits' })).toBe('pipeline/cdr_ingest');
    });
});

describe('signalToEvent projection', () => {
    it('projects the envelope onto the EventRow view + display extras', () => {
        const e = signalToEvent(baseSignal);
        expect(e.eventId).toBe('s1');
        expect(e.ts).toBe(baseSignal.at);
        expect(e.level).toBe('INFO');
        expect(e.source).toBe('pipeline/cdr_ingest');
        expect(e.pipeline).toBe('cdr_ingest');
        expect(e.message).toBe('BATCH_COMMITTED on cdr_ingest');
        expect(e.attributes).toEqual({ rows: '42' });
        expect(e.severity).toBe('info');
        expect(e.sourceRef).toEqual(baseSignal.source);
    });

    it('falls back to the type as the message and empty attributes', () => {
        const e = signalToEvent({ ...baseSignal, payload: {} });
        expect(e.message).toBe('BATCH_COMMITTED');
        expect(e.pipeline).toBeNull();
        expect(e.attributes).toEqual({});
    });
});

describe('alert signals', () => {
    const alertSignal: Signal = {
        signalId: 'alert-9', type: 'ALERT_FIRED', at: 1_700_000_000_000,
        source: { kind: 'alert-rule', id: 'high_error_rate', rel: 'emits' }, severity: 'critical',
        payload: { rule: 'high_error_rate', pipeline: 'cdr_ingest', metric: 'error_rate', value: 0.3, comparator: 'gt', threshold: 0.1, window: '15m', message: 'threshold exceeded' },
    };

    it('recognises an alert signal', () => {
        expect(isAlertSignal(alertSignal)).toBe(true);
        expect(isAlertSignal(baseSignal)).toBe(false);
    });

    it('projects onto the FiredAlert view', () => {
        const a = signalToAlert(alertSignal);
        expect(a.rule).toBe('high_error_rate');
        expect(a.severity).toBe('CRITICAL');
        expect(a.metric).toBe('error_rate');
        expect(a.value).toBe(0.3);
        expect(a.threshold).toBe(0.1);
        expect(a.epochMillis).toBe(alertSignal.at);
    });

    it('is a notify-worthy type with derived fanOut metadata', () => {
        expect(NOTIFY_TYPES.has('ALERT_FIRED')).toBe(true);
        const meta = notifyMeta(alertSignal);
        expect(meta).toEqual({ category: 'OPS', title: 'Alert: high_error_rate', body: 'error_rate on cdr_ingest', sourceId: 'high_error_rate' });
        expect(notifyMeta(baseSignal)).toBeNull();
    });
});

describe('seed adapters round-trip', () => {
    it('eventToSignal → signalToEvent preserves the level/type/message/attributes', () => {
        const row: EventRow = {
            eventId: 'evt-1', ts: 123, timestamp: new Date(123).toISOString(), level: 'WARN', type: 'FILE_QUARANTINED',
            source: 'engine', pipeline: 'voucher_etl', correlationId: null, message: 'quarantined', attributes: { n: '1' },
        };
        const back = signalToEvent(eventToSignal(row));
        expect(back.level).toBe('WARN');
        expect(back.type).toBe('FILE_QUARANTINED');
        expect(back.source).toBe('engine');
        expect(back.pipeline).toBe('voucher_etl');
        expect(back.message).toBe('quarantined');
        expect(back.attributes).toEqual({ n: '1' });
    });

    it('alertToSignal → signalToAlert preserves the alert fields and uses the given id', () => {
        const alert: FiredAlert = {
            rule: 'slow_batch', severity: 'WARNING', pipeline: 'cdr_ingest', metric: 'duration_ms',
            value: 45_000, comparator: 'gt', threshold: 30_000, window: '15m', epochMillis: 999, message: 'slow',
        };
        const sig = alertToSignal(alert, 'alert-ra-1');
        expect(sig.signalId).toBe('alert-ra-1');
        expect(sig.type).toBe('ALERT_FIRED');
        const back = signalToAlert(sig);
        expect(back.rule).toBe('slow_batch');
        expect(back.severity).toBe('WARNING');
        expect(back.value).toBe(45_000);
    });
});
