import { describe, expect, it } from 'vitest';
import { fmtDuration, scheduleSummary, typeLabel, whatScheduled } from './job-display';

describe('job-display', () => {
    it('fmtDuration formats sub-second / second / minute / null', () => {
        expect(fmtDuration(450)).toBe('450ms');
        expect(fmtDuration(1500)).toBe('1.5s');
        expect(fmtDuration(123_000)).toBe('2m 03s');
        expect(fmtDuration(null)).toBe('—');
    });

    it('typeLabel maps known types and passes others through', () => {
        expect(typeLabel('report')).toBe('Report');
        expect(typeLabel('custom')).toBe('custom');
    });

    it('whatScheduled adds a param hint when one is present', () => {
        expect(whatScheduled({ type: 'report', params: { report: 'daily_summary' } })).toBe('Report · daily_summary');
        expect(whatScheduled({ type: 'flow', params: { flow: 'cdr_export' } })).toBe('Flow · cdr_export');
        expect(whatScheduled({ type: 'ingest' })).toBe('Ingest');
    });

    it('scheduleSummary covers cron / event / manual', () => {
        expect(scheduleSummary({ cron: '0 0 6 * * *' })).toBe('0 0 6 * * *');
        expect(scheduleSummary({ onPipeline: 'cdr_ingest' })).toBe('on cdr_ingest');
        expect(scheduleSummary({})).toBe('manual');
    });
});
