import type { JobDetail, JobRunLogs } from '../api/jobs.service';
import type { JobRun } from '../api/models';
import { levelToSeverity } from '../signal/signal';
import { JOB_RUN_LOGS_COLL, JOB_RUNS_COLL, JOBS_COLL, recordRun } from './handlers/jobs.handler';
import { MockFlags } from './mock-flags';
import { MockStore } from './mock-store';
import { emitSignal } from './signals';

/**
 * The liveness simulator (plan W1) — ticks Runs / Events / Alerts so the Ops screens feel real in
 * mock mode: RUNNING job runs complete, a fresh event lands each tick, an alert fires now and then.
 *
 * Deliberately timer-free: {@link maybeTick} is called by the mock interceptor on every intercepted
 * request and advances the world at most once per {@link TICK_MS} per space — polling screens
 * (live-tail, events) drive their own liveness, and an idle tab mutates nothing. Framework-free and
 * deterministic given (store, now), so it unit-tests in plain vitest.
 */

export const TICK_MS = 15_000;
/** RUNNING runs older than this complete on the next tick. */
const RUN_COMPLETES_AFTER_MS = 90_000;

const lastTick = new Map<string, number>();

/** Tick at most once per {@link TICK_MS} per space; cheap no-op otherwise. */
export function maybeTick(store: MockStore, space: string, flags: MockFlags, now = Date.now()): void {
    if (!flags.mockOps && !flags.mockJobs) return;
    if (now - (lastTick.get(space) ?? 0) < TICK_MS) return;
    lastTick.set(space, now);
    simulateTick(store, space, flags, now);
}

/** One world step (exported for tests — `maybeTick` is the rate-limited entry point). */
export function simulateTick(store: MockStore, space: string, flags: MockFlags, now: number): void {
    const n = Math.floor(now / TICK_MS); // slowly-rotating variety selector
    if (flags.mockOps) {
        appendEvent(store, space, now, n);
        if (n % 5 === 0) fireAlert(store, space, now, n);
    }
    if (flags.mockJobs) {
        completeStaleRuns(store, space, now);
        if (n % 4 === 0) startCronRun(store, space, now, n);
    }
}

// ── events / alerts ─────────────────────────────────────────────────────────

const PIPELINES = ['cdr_ingest', 'subscriber_load', 'voucher_etl'];
const EVENT_KINDS: Array<{ level: string; type: string }> = [
    { level: 'INFO', type: 'BATCH_COMMITTED' },
    { level: 'INFO', type: 'FILE_RECEIVED' },
    { level: 'WARN', type: 'FILE_QUARANTINED' },
    { level: 'INFO', type: 'JOB_SUCCEEDED' },
    { level: 'ERROR', type: 'BATCH_FAILED' },
    { level: 'INFO', type: 'BATCH_COMMITTED' },
];

function appendEvent(store: MockStore, space: string, now: number, n: number): void {
    const kind = EVENT_KINDS[n % EVENT_KINDS.length];
    const pipeline = PIPELINES[n % PIPELINES.length];
    emitSignal(store, space, {
        signalId: `evt-${now}`,
        type: kind.type,
        at: now,
        source: { kind: 'pipeline', id: pipeline, rel: 'emits' },
        correlationId: n % 4 === 0 ? 'corr-' + (n % 5) : null,
        severity: levelToSeverity(kind.level),
        payload: {
            message: `${kind.type} on ${pipeline}`,
            pipeline,
            attributes: { rows: String((n * 137) % 5000), node: 'node-' + (n % 3) },
        },
    });
}

function fireAlert(store: MockStore, space: string, now: number, n: number): void {
    const rule = ['high_error_rate', 'rejected_spike', 'slow_batch'][n % 3];
    const pipeline = PIPELINES[n % PIPELINES.length];
    const metric = ['error_rate', 'rejected_files', 'duration_ms'][n % 3];
    // emitSignal appends to the ledger and fans out the notification (ALERT_FIRED is a notify type).
    emitSignal(store, space, {
        signalId: `alert-${now}`,
        type: 'ALERT_FIRED',
        at: now,
        source: { kind: 'alert-rule', id: rule, rel: 'emits' },
        correlationId: null,
        severity: (['info', 'warn', 'critical'] as const)[n % 3],
        payload: {
            rule, pipeline, metric,
            value: [0.12, 7, 45_000][n % 3] + (n % 10),
            comparator: 'gt',
            threshold: [0.1, 5, 30_000][n % 3],
            window: '15m',
            message: 'threshold exceeded',
        },
    });
}

// ── job runs ────────────────────────────────────────────────────────────────

function completeStaleRuns(store: MockStore, space: string, now: number): void {
    for (const run of store.list<JobRun>(space, JOB_RUNS_COLL)) {
        const started = run.startTime ? Date.parse(run.startTime) : now;
        if (run.status !== 'RUNNING' || now - started < RUN_COMPLETES_AFTER_MS) continue;
        const done: JobRun = {
            ...run,
            status: 'SUCCESS',
            endTime: new Date(now).toISOString(),
            durationMs: now - started,
        };
        store.put(space, JOB_RUNS_COLL, run.runId, done);
        const logs = store.get<JobRunLogs>(space, JOB_RUN_LOGS_COLL, run.runId);
        if (logs) {
            store.put(space, JOB_RUN_LOGS_COLL, run.runId, {
                ...logs,
                logs: [...logs.logs, { ts: done.endTime!, level: 'INFO', message: 'Completed: SUCCESS.' }],
            });
        }
        const job = store.get<JobDetail>(space, JOBS_COLL, run.jobName);
        if (job) store.put(space, JOBS_COLL, job.name, { ...job, lastStatus: 'SUCCESS', lastRunTime: run.startTime });
    }
}

function startCronRun(store: MockStore, space: string, now: number, n: number): void {
    const candidates = store.list<JobDetail>(space, JOBS_COLL).filter((j) => j.enabled && j.cron);
    if (!candidates.length) return;
    const job = candidates[n % candidates.length];
    if (store.list<JobRun>(space, JOB_RUNS_COLL).some((r) => r.jobName === job.name && r.status === 'RUNNING')) return;
    recordRun(store, space, job.name, 'CRON', 'RUNNING', now, 0, `Scheduled run of "${job.name}"…`);
    store.put(space, JOBS_COLL, job.name, { ...job, lastStatus: 'RUNNING', lastRunTime: new Date(now).toISOString() });
}
