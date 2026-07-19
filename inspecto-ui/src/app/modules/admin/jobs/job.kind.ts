import type { JobDetail } from 'app/inspecto/api/jobs.service';
import { ComponentKind, ConfigFinding, Part, Ref, Wiring, getKind, hasEditorRoute, jobRefs, registerEditorRoute, registerKind } from 'app/inspecto/component-model';

/**
 * The `job` {@link ComponentKind} — R2 of the living-operational-system roadmap: the Execution
 * network's unit joins the metadata network. A job is atomic (its pipeline is a *referenced*
 * artifact, not a part) and authors the **schedule** wiring: a cron, an upstream pipeline event
 * (`onPipeline` — also its `triggers` lineage edge), or neither (manual). Scheduler/trigger are
 * deliberately NOT separate kinds: cron/event are mutually exclusive fields with no second
 * consumer — the wiring variant is the trigger made first-class. Authoring = the jobs pane's
 * schema-driven form (`job-attributes.ts`); exec = the backend job runner.
 */
export const JOB_KIND: ComponentKind<JobDetail> = {
    id: 'job',
    label: 'Job',
    allowedPartKinds: [],
    wiring: 'schedule',
    config: {
        validate: validateJobConfig,
        create: () => ({ name: '', type: 'ingest', cron: null, onPipeline: null, enabled: true }) as JobDetail,
    },
    deriveWiring: (_parts: Part[], config: JobDetail): Wiring => ({
        strategy: 'schedule',
        cron: config.cron ?? undefined,
        on: config.onPipeline ?? undefined,
    }),
    deriveRefs: (config: JobDetail): Ref[] => jobRefs(config as unknown as Record<string, unknown>),
    authoring: { editorKey: 'job' },
    exec: { runnerKey: 'job' },
};

/** Tiny hand-written validator (the schema-form owns field-level UX): identity, a type, and at most
 *  one trigger — cron and onPipeline are mutually exclusive (neither = manual). */
export function validateJobConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<JobDetail>;
    const findings: ConfigFinding[] = [];
    if (!c.name || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(c.name)) {
        findings.push({ severity: 'error', path: 'name', message: 'Letters, digits, dot, dash, underscore; start alphanumeric.' });
    }
    if (!c.type) findings.push({ severity: 'error', path: 'type', message: 'Pick a job type.' });
    if (c.cron && c.onPipeline) {
        findings.push({ severity: 'error', path: 'cron', message: 'A job triggers on a cron OR a pipeline event, not both.' });
    }
    return findings;
}

if (!getKind(JOB_KIND.id)) {
    registerKind(JOB_KIND);
}
// The Jobs pane edits via dialogs (no /:id route) — the pane itself is the editor target.
if (!hasEditorRoute('job')) {
    registerEditorRoute('job', () => ['/jobs']);
}
