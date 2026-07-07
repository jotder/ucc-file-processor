import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Signal } from '../signal/signal';
import type { JobDetail } from '../api/jobs.service';
import type { JobRun } from '../api/models';
import { SIGNALS_COLL } from './signals';
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
    // Pin the clock: seeds and beat math (n % 4 / n % 5) must not depend on wall-clock time.
    beforeEach(() => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-01-01T12:00:00Z'));
    });
    afterEach(() => vi.useRealTimers());

    it('appends one event signal per tick', () => {
        const store = seededStore();
        const before = store.list<Signal>('default', SIGNALS_COLL).length;
        // Choose a tick off the every-5th alert beat so exactly one (event) signal lands.
        let now = Date.now();
        while (Math.floor(now / TICK_MS) % 5 === 0) now += TICK_MS;
        simulateTick(store, 'default', FLAGS, now);
        const signals = store.list<Signal>('default', SIGNALS_COLL);
        expect(signals.length).toBe(before + 1);
        expect(signals.some((s) => s.signalId === `evt-${now}`)).toBe(true);
    });

    it('fires an alert signal on the every-5th beat', () => {
        const store = seededStore();
        // n % 5 === 0 fires an alert alongside the event.
        const now = Math.ceil(Date.now() / (TICK_MS * 5)) * TICK_MS * 5;
        simulateTick(store, 'default', { mockOps: true }, now);
        expect(store.get<Signal>('default', SIGNALS_COLL, `alert-${now}`)?.type).toBe('ALERT_FIRED');
    });

    it('completes a stale RUNNING run and updates its job', () => {
        const store = seededStore();
        const stale = store
            .list<JobRun>('default', JOB_RUNS_COLL)
            .find((r) => r.jobName === 'cdr_ingest_daily' && r.status === 'RUNNING')!;
        expect(stale).toBeDefined();
        // Seeded 5 minutes ago — already past the completion threshold. Tick off the every-4th
        // start-run beat, or startCronRun could immediately restart the job it just completed.
        let now = Date.now();
        while (Math.floor(now / TICK_MS) % 4 === 0) now += TICK_MS;
        simulateTick(store, 'default', FLAGS, now);
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

    it('trims the signal ledger to its cap', () => {
        const store = seededStore();
        const base = Date.now();
        for (let i = 0; i < 300; i++) simulateTick(store, 'default', { mockOps: true }, base + i * TICK_MS);
        expect(store.list('default', SIGNALS_COLL).length).toBeLessThanOrEqual(250);
    });

    it('does nothing when both domains are flagged off', () => {
        const store = seededStore();
        const before = store.list('default', SIGNALS_COLL).length;
        simulateTick(store, 'default', {}, Date.now());
        expect(store.list('default', SIGNALS_COLL).length).toBe(before);
    });
});
