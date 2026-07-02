import { describe, expect, it } from 'vitest';
import type { EventRow } from '../api/events.service';
import type { JobDetail } from '../api/jobs.service';
import type { JobRun } from '../api/models';
import { EVENTS_COLL } from './handlers/ops.handler';
import { JOB_RUNS_COLL, JOBS_COLL } from './handlers/jobs.handler';
import { MockStore } from './mock-store';
import { seedDefaultSpace } from './seeds/default-space.seed';
import { simulateTick, TICK_MS } from './simulator';

const FLAGS = { mockOps: true, mockJobs: true };

function seededStore(): MockStore {
    const store = new MockStore();
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

describe('liveness simulator', () => {
    it('appends one event per tick', () => {
        const store = seededStore();
        const before = store.list<EventRow>('default', EVENTS_COLL).length;
        const now = Date.now();
        simulateTick(store, 'default', FLAGS, now);
        const events = store.list<EventRow>('default', EVENTS_COLL);
        expect(events.length).toBe(before + 1);
        expect(events.some((e) => e.eventId === `evt-${now}`)).toBe(true);
    });

    it('completes a stale RUNNING run and updates its job', () => {
        const store = seededStore();
        const stale = store
            .list<JobRun>('default', JOB_RUNS_COLL)
            .find((r) => r.jobName === 'cdr_ingest_daily' && r.status === 'RUNNING')!;
        expect(stale).toBeDefined();
        // Seeded 5 minutes ago — already past the completion threshold.
        simulateTick(store, 'default', FLAGS, Date.now());
        const run = store.get<JobRun>('default', JOB_RUNS_COLL, stale.runId)!;
        expect(run.status).toBe('SUCCESS');
        expect(run.endTime).toBeTruthy();
        expect(store.get<JobDetail>('default', JOBS_COLL, 'cdr_ingest_daily')!.lastStatus).toBe('SUCCESS');
    });

    it('starts at most one RUNNING run per job on the start-run beat', () => {
        const store = seededStore();
        // Pick a `now` on the every-4th-tick beat so startCronRun fires.
        const now = Math.ceil(Date.now() / (TICK_MS * 4)) * TICK_MS * 4;
        simulateTick(store, 'default', FLAGS, now);
        simulateTick(store, 'default', FLAGS, now); // same beat again — must not double-start
        const runningByJob = new Map<string, number>();
        for (const r of store.list<JobRun>('default', JOB_RUNS_COLL)) {
            if (r.status === 'RUNNING') runningByJob.set(r.jobName, (runningByJob.get(r.jobName) ?? 0) + 1);
        }
        for (const count of runningByJob.values()) expect(count).toBe(1);
    });

    it('trims the event history to its cap', () => {
        const store = seededStore();
        const base = Date.now();
        for (let i = 0; i < 250; i++) simulateTick(store, 'default', { mockOps: true }, base + i * TICK_MS);
        expect(store.list('default', EVENTS_COLL).length).toBeLessThanOrEqual(200);
    });

    it('does nothing when both domains are flagged off', () => {
        const store = seededStore();
        const before = store.list('default', EVENTS_COLL).length;
        simulateTick(store, 'default', {}, Date.now());
        expect(store.list('default', EVENTS_COLL).length).toBe(before);
    });
});
