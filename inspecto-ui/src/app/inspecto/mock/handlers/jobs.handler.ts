import type { ComponentDef } from '../../api/components.service';
import type { JobDetail, JobRunLogs, JobUpsert } from '../../api/jobs.service';
import type { JobRun, JobView } from '../../api/models';
import { componentCollection } from './components.handler';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { fanOut } from '../notify';

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
export const REPORT_ARTIFACTS_COLL = 'report-artifact';

/** A generated export (C6) — the mock-only payload behind a report job's completed run. */
export interface ReportArtifact {
    runId: string;
    filename: string;
    mime: string;
    /** CSV: the raw text. PDF/PNG: a short placeholder string (no real rendering in mock). */
    content: string;
}

const RESERVED = new Set(['metrics', 'runs', 'failures', 'types']); // /jobs/<reserved> are real routes, not job ids

/**
 * Job Type descriptors (R3, GET /jobs/types[/{id}]) — mirrors the backend registry so the authoring
 * form is descriptor-driven offline too. `type` values are the framework `ParamType` names.
 */
const JOB_TYPE_DESCRIPTORS = [
    {
        id: 'enrich', title: 'Enrichment',
        description: 'Runs a Stage-2 enrichment once (full recompute) and publishes a chain commit.',
        parameters: [{ name: 'config', type: 'STRING', required: true, deduce: '', default: '', description: 'Path to the enrichment .toon' }],
        emits: ['pipeline.commit'], artifacts: [],
    },
    {
        id: 'report', title: 'Report',
        description: 'Computes a report (status / batch / dataset export) and optionally delivers it.',
        parameters: [
            { name: 'scope', type: 'STRING', required: false, deduce: '', default: 'status', description: 'status | batch | dataset' },
            { name: 'out_dir', type: 'STRING', required: false, deduce: '', default: '', description: 'Delivery directory (enables artifact + REPORT_READY)' },
            { name: 'format', type: 'STRING', required: false, deduce: '', default: '', description: 'json | csv' },
            { name: 'dataset', type: 'DATASET_REF', required: false, deduce: '', default: '', description: 'Dataset id (scope=dataset)' },
        ],
        emits: [], artifacts: [{ name: 'report', kind: 'report' }],
    },
    {
        id: 'maintenance', title: 'Maintenance',
        description: 'Built-in housekeeping task (cleanup / ledger_prune / db_maintenance / compact / materialize).',
        parameters: [
            { name: 'task', type: 'STRING', required: false, deduce: '', default: 'cleanup', description: 'Which maintenance task' },
            { name: 'dir', type: 'STRING', required: false, deduce: '', default: '', description: 'Target directory (cleanup / compact)' },
            { name: 'retention_days', type: 'INTEGER', required: false, deduce: '', default: '7', description: 'Age threshold in days' },
            { name: 'store', type: 'STRING', required: false, deduce: '', default: '', description: 'Store(s) a delete task targets (fenced)' },
        ],
        emits: [], artifacts: [],
    },
    {
        id: 'pipeline', title: 'Pipeline',
        description: 'Runs an authored Pipeline over data at rest; emits a commit downstream jobs can chain on.',
        parameters: [
            { name: 'flow', type: 'STRING', required: true, deduce: '', default: '', description: 'Authored Pipeline id to run' },
            { name: 'incremental_column', type: 'STRING', required: false, deduce: '', default: '', description: 'Watermark column for incremental runs' },
        ],
        emits: ['pipeline.commit'], artifacts: [],
    },
    {
        id: 'sql.template', title: 'Templated SQL',
        description: 'Runs an authored SQL template over source Datasets and materializes the result as a queryable Dataset.',
        parameters: [
            { name: 'sql', type: 'STRING', required: true, deduce: '', default: '', description: 'SQL SELECT template; its $name tokens are the runtime parameters' },
            { name: 'sink_dataset', type: 'STRING', required: true, deduce: '', default: '', description: 'Output Dataset (store dir under the data root)' },
            { name: 'sources', type: 'STRING', required: false, deduce: '', default: '', description: 'CSV of source store names to register as views' },
        ],
        emits: ['job.dataset.produced'], artifacts: [{ name: 'output', kind: 'dataset' }],
    },
];

const JOBS = /\/jobs$/;
const JOB_TYPES = /\/jobs\/types$/;
const JOB_TYPE_ONE = /\/jobs\/types\/([^/]+)$/;
const JOB_RUN_LOGS = /\/jobs\/([^/]+)\/runs\/([^/]+)\/logs$/;
const JOB_RUN_ARTIFACT = /\/jobs\/([^/]+)\/runs\/([^/]+)\/artifact$/;
const JOB_ARTIFACTS_LATEST = /\/jobs\/([^/]+)\/artifacts\/latest$/;
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

        if (method === 'GET' && (m = match(url, JOB_RUN_ARTIFACT))) {
            const artifact = store.get<ReportArtifact>(space, REPORT_ARTIFACTS_COLL, m[2]);
            return artifact ? json(artifact) : error(404, `no artifact for run ${m[2]}`);
        }
        // Run Artifacts of the latest successful run (R7) — feeds the Maintenance Overview (MNT-11).
        // Name-keyed demo shapes: a *backup* job shows an archive, a *storage* job the axis series.
        if (method === 'GET' && (m = match(url, JOB_ARTIFACTS_LATEST))) {
            const job = m[1];
            const base = { runId: `${job}-latest`, job, at: new Date().toISOString(), rows: 0, ref: null as string | null };
            if (job.includes('backup')) {
                return json([{ ...base, seq: 1, name: 'backup', kind: 'file',
                    ref: `data/backups/${job}_20260712.zip`, bytes: 48_213 }]);
            }
            if (job.includes('storage')) {
                return json([
                    { ...base, seq: 1, name: 'axis:config', kind: 'file', ref: 'config', bytes: 182_000 },
                    { ...base, seq: 2, name: 'axis:data', kind: 'file', ref: 'data', bytes: 9_412_000 },
                    { ...base, seq: 3, name: 'axis:audit', kind: 'file', ref: 'audit', bytes: 731_000 },
                ]);
            }
            return json([]);
        }
        if (method === 'GET' && JOB_TYPES.test(url)) return json(JOB_TYPE_DESCRIPTORS);
        if (method === 'GET' && (m = match(url, JOB_TYPE_ONE))) {
            const d = JOB_TYPE_DESCRIPTORS.find((t) => t.id === m![1]);
            return d ? json(d) : error(404, `no job type ${m[1]}`);
        }
        if (method === 'GET' && (m = match(url, JOB_RUN_LOGS))) return json(runLogs(store, space, m[2]));
        if (method === 'GET' && (m = match(url, JOB_RUNS))) return json(runsOf(store, space, m[1]));
        if (method === 'POST' && (m = match(url, JOB_TRIGGER))) return json(trigger(store, space, m[1]), 202);
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

/** v1 async contract (W5): the trigger answers 202 + the submitted run's id (the run itself is
 *  recorded synchronously here — the mock has no executor to wait on). */
function trigger(store: MockStore, space: string, name: string): { runId: string } {
    const job = store.get<JobDetail>(space, JOBS_COLL, name);
    if (!job) return { runId: `run-unknown-${name}` };
    // Only C6 dashboard-export jobs (identified by `params.dashboardId`) get the export treatment —
    // `type: 'report'` predates C6 and also covers other report jobs (e.g. the seeded billing report).
    const run = job.params?.['dashboardId']
        ? runReportExport(store, space, job)
        : recordRun(store, space, name, 'MANUAL', 'SUCCESS', Date.now(), 1_200, `Manual run of "${name}".`);
    store.put(space, JOBS_COLL, name, { ...job, lastStatus: run.status, lastRunTime: run.startTime });
    return { runId: run.runId };
}

/**
 * Run a scheduled Dashboard export (C6): CSV is a real serialization of the dashboard's tiles; PDF/PNG
 * are mock placeholders (no rendering engine here). Stores the artifact for download and fans out
 * REPORT_EXPORTED — the same shared notification core as Alerts/Expectations.
 */
function runReportExport(store: MockStore, space: string, job: JobDetail): JobRun {
    const dashboardId = String(job.params?.['dashboardId'] ?? '');
    const format = String(job.params?.['format'] ?? 'csv');
    const dashboard = store.get<ComponentDef>(space, componentCollection('dashboard'), dashboardId);
    const tiles = (dashboard?.content?.['tiles'] as unknown[] | undefined) ?? [];
    const startedAt = Date.now();

    if (!dashboard) {
        return recordRun(store, space, job.name, 'MANUAL', 'FAILED', startedAt, 400, `Dashboard "${dashboardId}" no longer exists.`);
    }

    const run = recordRun(
        store,
        space,
        job.name,
        'MANUAL',
        'SUCCESS',
        startedAt,
        800,
        `Exported "${dashboardId}" as ${format.toUpperCase()} (${tiles.length} tile(s)).`,
    );

    const artifact: ReportArtifact =
        format === 'csv'
            ? {
                  runId: run.runId,
                  filename: `${dashboardId}.csv`,
                  mime: 'text/csv',
                  content: ['tile_index,widget_id,span', ...tiles.map((t, i) => `${i},${(t as { widgetId?: string }).widgetId ?? ''},${(t as { span?: number }).span ?? 1}`)].join('\n'),
              }
            : {
                  runId: run.runId,
                  filename: `${dashboardId}.${format}`,
                  mime: format === 'pdf' ? 'application/pdf' : 'image/png',
                  content: `Mock ${format.toUpperCase()} snapshot of dashboard "${dashboardId}" (${tiles.length} tile(s)) — no rendering engine in mock mode.`,
              };
    store.put(space, REPORT_ARTIFACTS_COLL, run.runId, artifact);

    const recipients = (job.params?.['recipients'] as string[] | undefined) ?? [];
    fanOut(
        store,
        space,
        'REPORT_EXPORTED',
        'OPS',
        `Report ready: ${job.name}`,
        `"${dashboardId}" exported as ${format.toUpperCase()}${recipients.length ? ` for ${recipients.join(', ')}` : ''}.`,
        run.runId,
    );
    return run;
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
