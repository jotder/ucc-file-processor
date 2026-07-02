import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Job kind's attribute declarations (W2 pilot) — drives `<inspecto-schema-form>` in
 * {@link JobFormDialog}. Tier assignments per the attribute audit: identity + trigger are required,
 * arming is optional, `catchUp` is advanced (it was silently missing from the old hand-built form).
 */
export const JOB_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'name', label: 'Job id', type: 'string', tier: 'required',
        pattern: '[A-Za-z0-9][A-Za-z0-9._-]*',
        placeholder: 'e.g. cdr_ingest_daily',
        help: 'Letters, digits, dot, dash, underscore; start alphanumeric.',
    },
    {
        key: 'type', label: 'Type', type: 'select', tier: 'required', default: 'ingest',
        options: [
            { value: 'ingest', label: 'ingest' },
            { value: 'enrich', label: 'enrich' },
            { value: 'report', label: 'report' },
            { value: 'maintenance', label: 'maintenance' },
            { value: 'flow', label: 'flow' },
        ],
    },
    {
        key: 'scheduleMode', label: 'Trigger', type: 'select', tier: 'required', default: 'cron',
        options: [
            { value: 'cron', label: 'Cron schedule' },
            { value: 'event', label: 'On pipeline (event-driven)' },
            { value: 'manual', label: 'Manual only' },
        ],
    },
    {
        key: 'cron', label: 'Cron expression', type: 'string', tier: 'required',
        dependsOn: { key: 'scheduleMode', equals: 'cron' },
        default: '0 0 6 * * *',
        pattern: '\\S+(\\s+\\S+){4,5}',
        placeholder: '0 0 6 * * *',
        help: '5 or 6 fields (sec min hour day month weekday)',
    },
    {
        key: 'onPipeline', label: 'On pipeline', type: 'string', tier: 'required',
        dependsOn: { key: 'scheduleMode', equals: 'event' },
        placeholder: 'e.g. cdr_ingest',
        help: 'Runs when this pipeline commits a batch.',
    },
    { key: 'enabled', label: 'Enabled (armed)', type: 'boolean', tier: 'optional', default: true },
    {
        key: 'catchUp', label: 'Catch up missed fires', type: 'boolean', tier: 'advanced', default: false,
        help: 'Run once at startup when a scheduled fire was missed while the server was down.',
    },
];
