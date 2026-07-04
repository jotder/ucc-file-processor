import { describe, expect, it } from 'vitest';
import type { JobDetail, JobUpsert, ReportArtifact } from '../../api/jobs.service';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { componentCollection } from './components.handler';
import { jobsHandler } from './jobs.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

/** The default space seed has no Dashboard (only the vertical template packs do) — plant one directly. */
function seededStore(): MockStore {
    const store = new MockStore();
    store.ensureSeeded('default', seedDefaultSpace);
    store.put('default', componentCollection('dashboard'), 'cdr_sample', {
        type: 'dashboard',
        name: 'cdr_sample',
        ref: 'dashboard/cdr_sample',
        content: { tiles: [{ widgetId: 'w1', span: 1 }, { widgetId: 'w2', span: 2 }] },
    });
    return store;
}

/** Scheduled export ⇒ a `type:'report'` job (C6, no new entity). */
const REPORT_JOB: JobUpsert = {
    name: 'daily_cdr_export',
    type: 'report',
    cron: '0 0 6 * * *',
    enabled: true,
    params: { reportKind: 'dashboard', dashboardId: 'cdr_sample', format: 'csv', recipients: ['ops@x.com'] },
};

describe('jobsHandler — scheduled report exports (C6)', () => {
    const handler = jobsHandler({ mockJobs: true, mockOps: true, mockStudio: true });

    it('triggering a report job produces a downloadable CSV artifact and fans out REPORT_EXPORTED', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', REPORT_JOB), store);

        const result = handler(req('POST', '/api/jobs/daily_cdr_export/trigger'), store)?.body as { status: string };
        expect(result.status).toBe('SUCCESS');

        const job = handler(req('GET', '/api/jobs/daily_cdr_export'), store)?.body as JobDetail;
        expect(job.lastStatus).toBe('SUCCESS');

        const runs = handler(req('GET', '/api/jobs/daily_cdr_export/runs'), store)?.body as { runId: string }[];
        expect(runs.length).toBe(1);

        const artifact = handler(req('GET', `/api/jobs/daily_cdr_export/runs/${runs[0].runId}/artifact`), store)?.body as ReportArtifact;
        expect(artifact.mime).toBe('text/csv');
        expect(artifact.content).toContain('tile_index,widget_id,span');

        const notifs = store
            .list<{ sourceType: string; sourceId: string }>('default', 'notification')
            .filter((n) => n.sourceType === 'REPORT_EXPORTED');
        expect(notifs.length).toBe(1);
        expect(notifs[0].sourceId).toBe(runs[0].runId);
    });

    it('a PDF/PNG export produces a mock placeholder artifact with the right mime type', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', { ...REPORT_JOB, name: 'weekly_pdf', params: { ...REPORT_JOB.params, format: 'pdf' } }), store);
        handler(req('POST', '/api/jobs/weekly_pdf/trigger'), store);
        const runs = handler(req('GET', '/api/jobs/weekly_pdf/runs'), store)?.body as { runId: string }[];
        const artifact = handler(req('GET', `/api/jobs/weekly_pdf/runs/${runs[0].runId}/artifact`), store)?.body as ReportArtifact;
        expect(artifact.mime).toBe('application/pdf');
        expect(artifact.filename).toBe('cdr_sample.pdf');
    });

    it('triggering against a deleted dashboard FAILs the run instead of raising', () => {
        const store = seededStore();
        handler(
            req('POST', '/api/jobs', { ...REPORT_JOB, name: 'orphaned_export', params: { ...REPORT_JOB.params, dashboardId: 'gone' } }),
            store,
        );
        const result = handler(req('POST', '/api/jobs/orphaned_export/trigger'), store)?.body as { status: string };
        expect(result.status).toBe('FAILED');
    });

    it('a plain (non-report) job trigger is unaffected — no artifact, existing MANUAL/SUCCESS behavior', () => {
        const store = seededStore();
        const result = handler(req('POST', '/api/jobs/cdr_ingest_daily/trigger'), store)?.body as { status: string };
        expect(result.status).toBe('SUCCESS');
    });
});
