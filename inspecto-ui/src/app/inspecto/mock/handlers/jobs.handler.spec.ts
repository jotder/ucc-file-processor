import { describe, expect, it } from 'vitest';
import type { JobDetail, JobRunLogs } from '../../api/jobs.service';
import type { JobRun, JobView } from '../../api/models';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { jobsHandler } from './jobs.handler';

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

describe('jobsHandler', () => {
    const handler = jobsHandler({ mockJobs: true });

    it('lists seeded jobs as JobViews (no params/catchUp) and gets the full detail', () => {
        const store = seededStore();
        const list = handler(req('GET', '/api/jobs'), store)?.body as JobView[];
        expect(list.map((j) => j.name)).toContain('cdr_ingest_daily');
        expect((list[0] as unknown as Record<string, unknown>)['params']).toBeUndefined();

        const detail = handler(req('GET', '/api/jobs/cdr_ingest_daily'), store)?.body as JobDetail;
        expect(detail.params).toEqual({ source: 'cdr_sftp_prod', scope: 'roaming' });
    });

    it('upserts, toggles, reschedules and deletes a job (with its runs)', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', { name: 'nightly_export', type: 'flow', cron: '0 0 3 * * *', enabled: true }), store);
        expect((handler(req('GET', '/api/jobs/nightly_export'), store)?.body as JobDetail).cron).toBe('0 0 3 * * *');

        const disabled = handler(req('POST', '/api/jobs/nightly_export/disable'), store)?.body as JobDetail;
        expect(disabled.enabled).toBe(false);

        const rescheduled = handler(req('POST', '/api/jobs/nightly_export/reschedule', { cron: '0 0 4 * * *' }), store)?.body as JobDetail;
        expect(rescheduled.cron).toBe('0 0 4 * * *');
        expect(rescheduled.nextFire).toBeTruthy();

        handler(req('POST', '/api/jobs/nightly_export/trigger'), store);
        expect((handler(req('GET', '/api/jobs/nightly_export/runs'), store)?.body as JobRun[]).length).toBe(1);

        expect(handler(req('DELETE', '/api/jobs/nightly_export'), store)?.body).toEqual({ deleted: true });
        expect(handler(req('GET', '/api/jobs/nightly_export'), store)).toBeUndefined(); // unknown id falls through
        expect(store.list('default', 'job-run').every((r) => (r as JobRun).jobName !== 'nightly_export')).toBe(true);
    });

    it('records a MANUAL run on trigger and reflects it on the job', () => {
        const store = seededStore();
        const res = handler(req('POST', '/api/jobs/weekly_billing/trigger'), store)?.body as { job: string; status: string };
        expect(res).toEqual({ job: 'weekly_billing', status: 'SUCCESS' });
        const runs = handler(req('GET', '/api/jobs/weekly_billing/runs'), store)?.body as JobRun[];
        expect(runs[0].triggerType).toBe('MANUAL'); // newest first
        const logs = handler(req('GET', `/api/jobs/weekly_billing/runs/${runs[0].runId}/logs`), store)?.body as JobRunLogs;
        expect(logs.logs.some((l) => l.message.includes('Manual run'))).toBe(true);
    });

    it('appends a live heartbeat log line while a run is RUNNING', () => {
        const store = seededStore();
        const runs = handler(req('GET', '/api/jobs/cdr_ingest_daily/runs'), store)?.body as JobRun[];
        const running = runs.find((r) => r.status === 'RUNNING')!;
        const logs = handler(req('GET', `/api/jobs/cdr_ingest_daily/runs/${running.runId}/logs`), store)?.body as JobRunLogs;
        expect(logs.logs[logs.logs.length - 1].message).toContain('still running');
    });

    it('lets the reserved reporting routes fall through to the real backend', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/jobs/metrics'), store)).toBeUndefined();
        expect(handler(req('GET', '/api/jobs/failures'), store)).toBeUndefined();
        expect(jobsHandler({ mockJobs: false })(req('GET', '/api/jobs'), store)).toBeUndefined();
    });
});
