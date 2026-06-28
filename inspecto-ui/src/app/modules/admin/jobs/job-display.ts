/** Pure display helpers shared by the Scheduler list + detail (framework-agnostic, vitest-pure). */

/** Format a duration in ms for display (e.g. `450ms`, `1.2s`, `2m 03s`). */
export function fmtDuration(ms: number | undefined | null): string {
    if (ms == null) return '—';
    if (ms < 1000) return `${ms}ms`;
    const s = ms / 1000;
    if (s < 60) return `${s.toFixed(1)}s`;
    const m = Math.floor(s / 60);
    return `${m}m ${String(Math.round(s % 60)).padStart(2, '0')}s`;
}

const TYPE_LABEL: Record<string, string> = {
    ingest: 'Ingest',
    enrich: 'Enrich',
    report: 'Report',
    maintenance: 'Maintenance',
    flow: 'Flow',
};

/** Friendly label for a job type. */
export function typeLabel(type: string): string {
    return TYPE_LABEL[type] ?? type;
}

/** "What's scheduled" — the job's type plus a key param hint when available (params only present on the detail). */
export function whatScheduled(job: { type: string; params?: Record<string, unknown> }): string {
    const label = typeLabel(job.type);
    const p = job.params ?? {};
    const hint = p['report'] ?? p['flow'] ?? p['task'] ?? p['scope'] ?? p['source'];
    return hint ? `${label} · ${hint}` : label;
}

/** "Schedule" — the cron expression, an event trigger, or manual-only. */
export function scheduleSummary(job: { cron?: string | null; onPipeline?: string | null }): string {
    return job.cron ? job.cron : job.onPipeline ? `on ${job.onPipeline}` : 'manual';
}
