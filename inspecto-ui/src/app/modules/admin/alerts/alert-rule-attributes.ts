import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Alert Rule kind's attribute declarations — drives `<inspecto-schema-form>` in
 * {@link AlertRuleFormDialog} (audit C3). An Alert Rule watches an observability **Metric**
 * against a threshold over a window (GLOSSARY §4/§8) — every field is a scalar, so the whole
 * form is spec-driven; nothing bespoke.
 */
// 'name' (the rule id) is asked at save time (ui-design-review R9 — name-at-save), not declared
// here; see AlertRuleFormDialog's `saveForm`.
export const ALERT_RULE_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'metric', label: 'Metric', type: 'autocomplete', tier: 'required',
        placeholder: 'e.g. error_rate, rejected_files, duration_ms',
        help: 'The observability metric to watch (as emitted by the engine).',
    },
    {
        key: 'comparator', label: 'Comparator', type: 'select', tier: 'required', default: 'gt',
        options: [
            { value: 'gt', label: '> greater than' },
            { value: 'gte', label: '≥ at least' },
            { value: 'lt', label: '< less than' },
            { value: 'lte', label: '≤ at most' },
            { value: 'eq', label: '= equals' },
        ],
    },
    { key: 'threshold', label: 'Threshold', type: 'number', tier: 'required', default: 0 },
    {
        key: 'window', label: 'Window', type: 'select', tier: 'required', default: '15m',
        options: [
            { value: '5m', label: '5 minutes' },
            { value: '15m', label: '15 minutes' },
            { value: '1h', label: '1 hour' },
            { value: '24h', label: '24 hours' },
        ],
        help: 'The evaluation window the metric is aggregated over.',
    },
    {
        key: 'severity', label: 'Severity', type: 'select', tier: 'required', default: 'WARNING',
        options: [
            { value: 'INFO', label: 'Info' },
            { value: 'WARNING', label: 'Warning' },
            { value: 'CRITICAL', label: 'Critical' },
        ],
    },
    {
        key: 'onPipeline', label: 'Pipeline scope', type: 'autocomplete', tier: 'optional',
        placeholder: 'e.g. cdr_ingest',
        help: 'Limit the rule to one Pipeline; leave blank to watch every Pipeline.',
    },
];
