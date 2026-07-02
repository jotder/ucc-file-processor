import { HttpEvent, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { JobDetail, JobLogLine, JobRunLogs, JobUpsert } from './jobs.service';
import { JobRun, JobView } from './models';

/**
 * PROTOTYPE-ONLY mock for the **Scheduler** (Operations). The backend already serves `GET /jobs`,
 * `GET /jobs/{name}/runs` and `POST /jobs/{name}/trigger`, but has no endpoints for the management actions
 * (create / edit / delete / enable / disable / reschedule) or per-run logs. This interceptor serves the whole
 * surface from an in-memory store so the page is fully clickable offline — same pattern as the unified mock store /
 * `flow-mock`. Gated on {@code environment.mockJobs}.
 *
 * **Falls through** (`next(req)`) for the DuckDB reporting endpoints (`/jobs/metrics`, `/jobs/runs`,
 * `/jobs/failures`) so the existing reporting view keeps its real-backend behavior (and its 404 empty state).
 * Flip the flag / remove the interceptor once the real Java endpoints land (see the plan's follow-on).
 */

const LATENCY_MS = 150;
const RESERVED = new Set(['metrics', 'runs', 'failures']); // /jobs/<reserved> are real reporting routes, not job ids

const JOBS = /\/jobs$/;
const JOB_RUN_LOGS = /\/jobs\/([^/]+)\/runs\/([^/]+)\/logs$/;
const JOB_RUNS = /\/jobs\/([^/]+)\/runs$/;
const JOB_TRIGGER = /\/jobs\/([^/]+)\/trigger$/;
const JOB_TOGGLE = /\/jobs\/([^/]+)\/(enable|disable)$/;
const JOB_RESCHEDULE = /\/jobs\/([^/]+)\/reschedule$/;
const JOB_ONE = /\/jobs\/([^/]+)$/;

function iso(offsetMin: number): string {
    return new Date(Date.now() + offsetMin * 60_000).toISOString();
}
let seq = 100;
const runId = (): string => `run-${++seq}`;

/** In-memory job store (full config), keyed by name. */
const STORE: Record<string, JobDetail> = {};
/** Per-job run history (newest first). */
const RUNS: Record<string, JobRun[]> = {};
/** Per-run logs + events. */
const LOGS: Record<string, JobRunLogs> = {};

function seedRun(job: string, trigger: string, status: string, startedMin: number, durationMs: number, message: string): JobRun {
    const id = runId();
    const running = status === 'RUNNING';
    RUNS[job] = RUNS[job] ?? [];
    const run: JobRun = {
        jobName: job,
        runId: id,
        status,
        triggerType: trigger,
        startTime: iso(startedMin),
        endTime: running ? undefined : iso(startedMin + Math.round(durationMs / 60_000)),
        durationMs: running ? undefined : durationMs,
        error: status === 'FAILED' ? message : null,
    };
    RUNS[job].unshift(run);
    LOGS[id] = {
        logs: [
            { ts: iso(startedMin), level: 'INFO', message: `Job "${job}" started (trigger=${trigger}).` },
            { ts: iso(startedMin), level: 'INFO', message: message },
            ...(status === 'FAILED' ? [{ ts: iso(startedMin + 1), level: 'ERROR' as const, message }] : []),
            ...(running ? [] : [{ ts: iso(startedMin + Math.round(durationMs / 60_000)), level: 'INFO' as const, message: `Completed: ${status}.` }]),
        ],
        events: [{ ts: iso(startedMin), type: 'JOB_STARTED', message: `${job} fired by ${trigger}` }],
    };
    return run;
}

function seedJob(j: JobDetail, runs: Array<[string, string, number, number, string]>): void {
    STORE[j.name] = j;
    RUNS[j.name] = [];
    for (const [trigger, status, startedMin, durationMs, message] of runs) {
        seedRun(j.name, trigger, status, startedMin, durationMs, message);
    }
}

seedJob(
    { name: 'cdr_ingest_daily', type: 'ingest', cron: '0 0 6 * * *', onPipeline: null, enabled: true, lastStatus: 'RUNNING', lastRunTime: iso(-5), nextFire: iso(60 * 18), catchUp: true, params: { source: 'cdr_sftp_prod', scope: 'roaming' } },
    [['CRON', 'RUNNING', -5, 0, 'Discovering files on cdr_sftp_prod…'], ['CRON', 'SUCCESS', -60 * 24, 142_000, 'Ingested 1,204 files.'], ['CRON', 'SUCCESS', -60 * 48, 138_500, 'Ingested 1,180 files.']],
);
seedJob(
    { name: 'enrich_roaming', type: 'enrich', cron: null, onPipeline: 'cdr_ingest', enabled: true, lastStatus: 'SUCCESS', lastRunTime: iso(-30), nextFire: null, params: { task: 'roaming_enrichment' } },
    [['EVENT', 'SUCCESS', -30, 9_200, 'Enriched 1,204 rows.'], ['EVENT', 'SUCCESS', -60 * 24, 8_900, 'Enriched 1,180 rows.']],
);
seedJob(
    { name: 'daily_summary_report', type: 'report', cron: '0 30 6 * * *', onPipeline: null, enabled: true, lastStatus: 'FAILED', lastRunTime: iso(-60 * 6), nextFire: iso(60 * 18), params: { report: 'daily_summary', store: 'reports' } },
    [['CRON', 'FAILED', -60 * 6, 4_300, 'Report query failed: store "reports" not found.'], ['CRON', 'SUCCESS', -60 * 30, 5_100, 'Wrote daily_summary.parquet.']],
);
seedJob(
    { name: 'catalog_maintenance', type: 'maintenance', cron: '0 0 2 * * 0', onPipeline: null, enabled: true, lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 50), nextFire: iso(60 * 110), catchUp: true, params: { task: 'vacuum' } },
    [['CRON', 'SUCCESS', -60 * 50, 61_000, 'Vacuumed 12 tables.']],
);
seedJob(
    { name: 'weekly_billing', type: 'report', cron: '0 0 1 * * 1', onPipeline: null, enabled: false, lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 24 * 5), nextFire: null, params: { report: 'billing' } },
    [['CRON', 'SUCCESS', -60 * 24 * 5, 22_000, 'Wrote billing.csv.']],
);
seedJob(
    { name: 'adhoc_export', type: 'flow', cron: null, onPipeline: null, enabled: true, lastStatus: undefined, lastRunTime: undefined, nextFire: null, params: { flow: 'cdr_export' } },
    [],
);

// Newest run first (run history + the detail's default selection); trigger() unshifts new runs to the front.
for (const name of Object.keys(RUNS)) RUNS[name].sort((a, b) => (b.startTime ?? '').localeCompare(a.startTime ?? ''));

/** Project the full record onto the list `JobView` (drop params/catchUp — the list endpoint omits them). */
function toView(j: JobDetail): JobView {
    return { name: j.name, type: j.type, cron: j.cron, onPipeline: j.onPipeline, enabled: j.enabled, lastStatus: j.lastStatus, lastRunTime: j.lastRunTime, nextFire: j.nextFire };
}

/** A plausible next-fire for a cron job (mock — the real backend uses CronExpression); null when not cron. */
function nextFireFor(cron: string | null | undefined): string | null {
    return cron ? iso(60) : null;
}

export const jobsMockInterceptor: HttpInterceptorFn = (req, next) => {
    if (!(environment as { mockJobs?: boolean }).mockJobs) return next(req);
    const url = req.url;
    let m: RegExpMatchArray | null;

    if (req.method === 'GET' && (m = url.match(JOB_RUN_LOGS))) return reply(getRunLogs(dec(m[2])));
    if (req.method === 'GET' && (m = url.match(JOB_RUNS))) return reply(RUNS[dec(m[1])] ?? []);
    if (req.method === 'POST' && (m = url.match(JOB_TRIGGER))) return reply(triggerJob(dec(m[1])));
    if (req.method === 'POST' && (m = url.match(JOB_TOGGLE))) return reply(setEnabled(dec(m[1]), m[2] === 'enable'));
    if (req.method === 'POST' && (m = url.match(JOB_RESCHEDULE))) return reply(reschedule(dec(m[1]), (req.body as { cron?: string })?.cron ?? ''));
    if (req.method === 'GET' && (m = url.match(JOBS))) return reply(Object.values(STORE).map(toView));
    if (req.method === 'POST' && url.match(JOBS)) return reply(upsert(req.body as JobUpsert));
    if (req.method === 'PUT' && (m = url.match(JOB_ONE))) return reply(upsert({ ...(req.body as JobUpsert), name: dec(m[1]) }));
    if (req.method === 'DELETE' && (m = url.match(JOB_ONE)) && STORE[dec(m[1])]) return reply(deleteJob(dec(m[1])));
    if (req.method === 'GET' && (m = url.match(JOB_ONE)) && !RESERVED.has(dec(m[1])) && STORE[dec(m[1])]) return reply(STORE[dec(m[1])]);

    return next(req); // reporting endpoints + unknown job ids fall through to the real backend
};

/** A run's logs; for a still-RUNNING run, append a fresh heartbeat line each call so live-tail visibly updates. */
function getRunLogs(id: string): JobRunLogs {
    const base = LOGS[id] ?? { logs: [], events: [] };
    const run = Object.values(RUNS).flat().find((r) => r.runId === id);
    if (run?.status === 'RUNNING') {
        const now = new Date();
        return { ...base, logs: [...base.logs, { ts: now.toISOString(), level: 'INFO', message: `…still running (${now.toLocaleTimeString()})` }] };
    }
    return base;
}

function triggerJob(name: string): { job: string; status: string } {
    const job = STORE[name];
    if (!job) return { job: name, status: 'UNKNOWN' };
    const run = seedRun(name, 'MANUAL', 'SUCCESS', 0, 1_200, `Manual run of "${name}".`);
    job.lastStatus = run.status;
    job.lastRunTime = run.startTime;
    return { job: name, status: run.status };
}

function setEnabled(name: string, enabled: boolean): JobDetail {
    const job = STORE[name];
    if (job) job.enabled = enabled;
    return job;
}

function reschedule(name: string, cron: string): JobDetail {
    const job = STORE[name];
    if (job) {
        job.cron = cron;
        job.onPipeline = null; // a cron schedule supersedes an event trigger
        job.nextFire = nextFireFor(cron);
    }
    return job;
}

function upsert(body: JobUpsert): JobDetail {
    const job: JobDetail = {
        name: body.name,
        type: body.type,
        cron: body.cron ?? null,
        onPipeline: body.onPipeline ?? null,
        enabled: body.enabled,
        catchUp: body.catchUp,
        params: body.params ?? {},
        lastStatus: STORE[body.name]?.lastStatus,
        lastRunTime: STORE[body.name]?.lastRunTime,
        nextFire: nextFireFor(body.cron),
    };
    STORE[job.name] = job;
    RUNS[job.name] = RUNS[job.name] ?? [];
    return job;
}

function deleteJob(name: string): { deleted: boolean } {
    delete STORE[name];
    delete RUNS[name];
    return { deleted: true };
}

function reply<T>(body: T): Observable<HttpEvent<unknown>> {
    return of(new HttpResponse({ status: 200, body })).pipe(delay(LATENCY_MS));
}

function dec(s: string): string {
    return decodeURIComponent(s);
}
