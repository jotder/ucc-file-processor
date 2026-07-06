import { describe, expect, it } from 'vitest';
import { JOB_KIND, validateJobConfig } from './job.kind';

describe('JOB_KIND (R2 — the Execution network joins the metadata network)', () => {
    it('authors the schedule wiring: cron, upstream pipeline event, or neither (manual)', () => {
        expect(JOB_KIND.deriveWiring!([], { name: 'j', type: 'ingest', cron: '0 0 6 * * *', onPipeline: null, enabled: true })).toEqual({
            strategy: 'schedule',
            cron: '0 0 6 * * *',
            on: undefined,
        });
        expect(JOB_KIND.deriveWiring!([], { name: 'j', type: 'enrich', cron: null, onPipeline: 'cdr_ingest', enabled: true })).toEqual({
            strategy: 'schedule',
            cron: undefined,
            on: 'cdr_ingest',
        });
    });

    it('derives the triggers lineage edge from onPipeline', () => {
        expect(JOB_KIND.deriveRefs!({ name: 'j', type: 'enrich', cron: null, onPipeline: 'cdr_ingest', enabled: true })).toEqual([
            { kind: 'pipeline', id: 'cdr_ingest', rel: 'triggers', via: 'onPipeline' },
        ]);
    });

    it('validates identity, type, and cron XOR onPipeline', () => {
        expect(validateJobConfig({ name: 'ok_job', type: 'ingest', cron: null, onPipeline: null })).toEqual([]);
        expect(validateJobConfig({ name: '', type: 'ingest' }).map((f) => f.path)).toContain('name');
        expect(validateJobConfig({ name: 'ok', type: undefined }).map((f) => f.path)).toContain('type');
        expect(
            validateJobConfig({ name: 'ok', type: 'enrich', cron: '0 * * * * *', onPipeline: 'cdr_ingest' }).map((f) => f.path),
        ).toContain('cron');
    });
});
