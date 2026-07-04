import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * Attribute declarations for scheduling a Dashboard export (C6) — drives `<inspecto-schema-form>` in
 * {@link ScheduleExportDialog}. A scheduled export IS a Job (`type: 'report'`); no new entity —
 * `dashboardId` and `format`/`recipients` live in the job's `params`.
 */
export const SCHEDULE_EXPORT_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'name', label: 'Schedule id', type: 'string', tier: 'required',
        pattern: '[A-Za-z0-9][A-Za-z0-9._-]*',
        placeholder: 'e.g. daily_sales_export',
        help: 'Letters, digits, dot, dash, underscore; start alphanumeric.',
    },
    {
        key: 'format', label: 'Export format', type: 'select', tier: 'required', default: 'csv',
        options: [
            { value: 'csv', label: 'CSV (tile data)' },
            { value: 'pdf', label: 'PDF (snapshot)' },
            { value: 'png', label: 'PNG (snapshot)' },
        ],
    },
    {
        key: 'scheduleMode', label: 'Trigger', type: 'select', tier: 'required', default: 'cron',
        options: [
            { value: 'cron', label: 'Cron schedule' },
            { value: 'manual', label: 'Manual only' },
        ],
    },
    {
        key: 'cron', label: 'Cron expression', type: 'string', tier: 'required',
        dependsOn: { key: 'scheduleMode', equals: 'cron' },
        default: '0 0 6 * * *',
        pattern: '\\S+(\\s+\\S+){4,5}',
        help: '5 or 6 fields (sec min hour day month weekday)',
    },
    {
        key: 'recipients', label: 'Recipients (comma-separated)', type: 'string', tier: 'optional', required: false,
        placeholder: 'ops@example.com, finance@example.com',
        help: 'Who is notified when the export completes.',
    },
    { key: 'enabled', label: 'Enabled (armed)', type: 'boolean', tier: 'optional', default: true },
];
