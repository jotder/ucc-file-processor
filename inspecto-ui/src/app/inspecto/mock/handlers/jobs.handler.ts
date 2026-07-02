import type { JobDetail, JobRunLogs, JobUpsert } from '../../api/jobs.service';
import type { JobRun, JobView } from '../../api/models';
import { MockFlags } from '../mock-flags';
import { json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The Scheduler mock domain — the port of the old `jobs-mock` interceptor onto the persistent
 * {@link MockStore}. The backend already serves `GET /jobs`, `GET /jobs/{name}/runs` and
 * `POST /jobs/{name}/trigger` but not the management actions (create / edit / delete / enable /
 * disable / reschedule) or per-run logs, so the whole surface is mocked while `mockJobs` is on.
 *
 * **Falls through** for the DuckDB reporting endpoints (`/jobs/metrics`, `/jobs/runs`,
 * `/jobs/failures`) and unknown job ids, so the reporting view keeps its real-backend behavior.
 * RUNNING runs are advanced to completion by the liveness simulator (`../simulator.ts`).
 */

export const JOBS_COLL = 'job';
export const JOB_RUNS_COLL = 'job-run';
export const JOB_RUN_LOGS_COLL = 'job-run-log';

const RESERVED = new Set(['metrics', 'runs', 'failures']); // /jobs/<reserved> are real reporting routes, not job ids

const JOBS = /\/jobs$/;
const JOB_RUN_LOGS = /\/jobs\/([^/]+)\/runs\/([^/]+)\/logs$/;
const JOB_RUNS = /\/jobs\/([^/]+)\/runs$/;
const JOB_TRIGGER = /\/jobs\/([^/]+)\/trigger$/;
const JOB_TOGGLE = /\/jobs\/([^/]+)\/(enable|disable)$/;
const JOB_RESCHEDULE = /\/jobs\/([^/]+)\/reschedule$/;
const JOB_ONE = /\/jobs\/([^/]+)$/;

export function jobsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockJobs) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'GET' && (m = match(url, JOB_RUN_LOGS))) return json(runLogs(store, space, m[2]));
        if (method === 'GET' && (m = match(url, JOB_RUNS))) return json(runsOf(store, space, m[1]));
        if (method === 'POST' && (m = match(url, JOB_TRIGGER))) return json(trigger(store, space, m[1]));
        if (method === 'POST' && (m = match(url, JOB_TOGGLE))) return json(setEnabled(store, space, m[1], m[2] === 'enable'));
        if (method === 'POST' && (m = match(url, JOB_RESCHEDULE))) {
            return json(reschedule(store, space, m[1], (req.body as { cron?: string })?.cron ?? ''));
        }
        if (method === 'GET' && JOBS.test(url)) return json(store.list<JobDetail>(space, JOBS_COLL).map(toView));
        if (method === 'POST' && JOBS.test(url)) return json(upsert(store, space, req.body as JobUpsert));
        if (method === 'PUT' && (m = match(url, JOB_ONE))) {
            return json(upsert(store, space, { ...(req.body as JobUpsert), name: m[1] }));
        }
        if (method === 'DELETE' && (m = match(url, JOB_ONE)) && store.has(space, JOBS_COLL, m[1])) {
            return json(deleteJob(store, space, m[1]));
        }
        if (method === 'GET' && (m = match(url, JOB_ONE)) && !RESERVED.has(m[1]) && store.has(space, JOBS_COLL, m[1])) {
            return json(store.get<JobDetail>(space, JOBS_COLL, m[1]));
        }
        return undefined; // reporting endpoints + unknown job ids fall through to the real backend
    };
}

/** Per-job run history, newest first. */
export function runsOf(store: MockStore, space: string, job: string): JobRun[] {
    return store
        .list<JobRun>(space, JOB_RUNS_COLL)
        .filter((r) => r.jobName === job)
        .sort((a, b) => (b.startTime ?? '').localeCompare(a.startTime ?? ''));
}

/** A run's logs; for a still-RUNNING run, append a fresh heartbeat line each call so live-tail visibly updates. */
function runLogs(store: MockStore, space: string, runId: string): JobRunLogs {
    const base = store.get<JobRunLogs>(space, JOB_RUN_LOGS_COLL, runId) ?? { logs: [], events: [] };
    const run = store.get<JobRun>(space, JOB_RUNS_COLL, runId);
    if (run?.status === 'RUNNING') {
        const now = new Date();
        return { ...base, logs: [...base.logs, { ts: now.toISOString(), level: 'INFO', message: `…still running (${now.toLocaleTimeString()})` }] };
    }
    return base;
}

/** Record one run + its logs (also used by the seed pack and the liveness simulator). */
export function recordRun(
    store: MockStore,
    space: string,
    job: string,
    trigger: string,
    status: string,
    startedAt: number,
    durationMs: number,
    message: string,
): JobRun {
    const id = `run-${startedAt}-${job}`;
    const running = status === 'RUNNING';
    const run: JobRun = {
        jobName: job,
        runId: id,
        status,
        triggerType: trigger,
        startTime: new Date(startedAt).toISOString(),
        endTime: running ? undefined : new Date(startedAt + durationMs).toISOString(),
        durationMs: running ? undefined : durationMs,
        error: status === 'FAILED' ? message : null,
    };
    store.put(space, JOB_RUNS_COLL, id, run);
    store.put(space, JOB_RUN_LOGS_COLL, id, {
        logs: [
            { ts: run.startTime, level: 'INFO', message: `Job "${job}" started (trigger=${trigger}).` },
            { ts: run.startTime, level: 'INFO', message },
            ...(status === 'FAILED' ? [{ ts: run.endTime!, level: 'ERROR' as const, message }] : []),
            ...(running ? [] : [{ ts: run.endTime!, level: 'INFO' as const, message: `Completed: ${status}.` }]),
        ],
        events: [{ ts: run.startTime, type: 'JOB_STARTED', message: `${job} fired by ${trigger}` }],
    } satisfies JobRunLogs);
    return run;
}

function trigger(store: MockStore, space: string, name: string): { job: string; status: string } {
    const job = store.get<JobDetail>(space, JOBS_COLL, name);
    if (!job) return { job: name, status: 'UNKNOWN' };
    const run = recordRun(store, space, name, 'MANUAL', 'SUCCESS', Date.now(), 1_200, `Manual run of "${name}".`);
    store.put(space, JOBS_COLL, name, { ...job, lastStatus: run.status, lastRunTime: run.startTime });
    return { job: name, status: run.status };
}

function setEnabled(store: MockStore, space: string, name: string, enabled: boolean): JobDetail | undefined {
    const job = store.get<JobDetail>(space, JOBS_COLL, name);
    return job ? store.put(space, JOBS_COLL, name, { ...job, enabled }) : undefined;
}

function reschedule(store: MockStore, space: string, name: string, cron: string): JobDetail | undefined {
    const job = store.get<JobDetail>(space, JOBS_COLL, name);
    if (!job) return undefined;
    // A cron schedule supersedes an event trigger.
    return store.put(space, JOBS_COLL, name, { ...job, cron, onPipeline: null, nextFire: nextFireFor(cron) });
}

function upsert(store: MockStore, space: string, body: JobUpsert): JobDetail {
    const existing = store.get<JobDetail>(space, JOBS_COLL, body.name);
    const job: JobDetail = {
        name: body.name,
        type: body.type,
        cron: body.cron ?? null,
        onPipeline: body.onPipeline ?? null,
        enabled: body.enabled,
        catchUp: body.catchUp,
        params: body.params ?? {},
        lastStatus: existing?.lastStatus,
        lastRunTime: existing?.lastRunTime,
        nextFire: nextFireFor(body.cron),
    };
    return store.put(space, JOBS_COLL, job.name, job);
}

function deleteJob(store: MockStore, space: string, name: string): { deleted: boolean } {
    store.delete(space, JOBS_COLL, name);
    for (const run of store.list<JobRun>(space, JOB_RUNS_COLL).filter((r) => r.jobName === name)) {
        store.delete(space, JOB_RUNS_COLL, run.runId);
        store.delete(space, JOB_RUN_LOGS_COLL, run.runId);
    }
    return { deleted: true };
}

/** Project the full record onto the list `JobView` (drop params/catchUp — the list endpoint omits them). */
function toView(j: JobDetail): JobView {
    return { name: j.name, type: j.type, cron: j.cron, onPipeline: j.onPipeline, enabled: j.enabled, lastStatus: j.lastStatus, lastRunTime: j.lastRunTime, nextFire: j.nextFire };
}

/** A plausible next-fire for a cron job (mock — the real backend uses CronExpression); null when not cron. */
function nextFireFor(cron: string | null | undefined): string | null {
    return cron ? new Date(Date.now() + 3_600_000).toISOString() : null;
}
