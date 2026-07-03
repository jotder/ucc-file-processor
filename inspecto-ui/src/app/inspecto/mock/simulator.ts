import type { EventRow } from '../api/events.service';
import type { FiredAlert } from '../api/alerts.service';
import type { JobDetail, JobRunLogs } from '../api/jobs.service';
import type { JobRun } from '../api/models';
import { EVENTS_COLL, FIRED_ALERTS_COLL } from './handlers/ops.handler';
import { JOB_RUN_LOGS_COLL, JOB_RUNS_COLL, JOBS_COLL, recordRun } from './handlers/jobs.handler';
import { MockFlags } from './mock-flags';
import { MockStore } from './mock-store';
import { fanOut } from './notify';

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
/** Cap stored history so the localStorage snapshot doesn't grow unbounded. */
const MAX_EVENTS = 200;
const MAX_ALERTS = 50;

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
    const event: EventRow = {
        eventId: `evt-${now}`,
        ts: now,
        timestamp: new Date(now).toISOString(),
        level: kind.level,
        type: kind.type,
        source: 'engine',
        pipeline,
        correlationId: n % 4 === 0 ? 'corr-' + (n % 5) : null,
        message: `${kind.type} on ${pipeline}`,
        attributes: { rows: String((n * 137) % 5000), node: 'node-' + (n % 3) },
    };
    store.put(space, EVENTS_COLL, event.eventId, event);
    trim(store, space, EVENTS_COLL, MAX_EVENTS, (e) => (e as EventRow).ts);
}

function fireAlert(store: MockStore, space: string, now: number, n: number): void {
    const alert: FiredAlert = {
        rule: ['high_error_rate', 'rejected_spike', 'slow_batch'][n % 3],
        severity: ['INFO', 'WARNING', 'CRITICAL'][n % 3],
        pipeline: PIPELINES[n % PIPELINES.length],
        metric: ['error_rate', 'rejected_files', 'duration_ms'][n % 3],
        value: [0.12, 7, 45_000][n % 3] + (n % 10),
        comparator: 'gt',
        threshold: [0.1, 5, 30_000][n % 3],
        window: '15m',
        epochMillis: now,
        message: 'threshold exceeded',
    };
    store.put(space, FIRED_ALERTS_COLL, `alert-${now}`, alert);
    trim(store, space, FIRED_ALERTS_COLL, MAX_ALERTS, (a) => (a as FiredAlert).epochMillis);
    fanOut(store, space, 'ALERT_FIRED', 'OPS', `Alert: ${alert.rule}`, `${alert.metric} on ${alert.pipeline}`, alert.rule);
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

/** Keep only the newest `max` entities of a collection, by the given timestamp extractor. */
function trim(store: MockStore, space: string, coll: string, max: number, tsOf: (e: unknown) => number): void {
    const rows = store.entries<unknown>(space, coll);
    if (rows.length <= max) return;
    const oldest = rows.sort(([, a], [, b]) => tsOf(a) - tsOf(b)).slice(0, rows.length - max);
    for (const [id] of oldest) store.delete(space, coll, id);
}
