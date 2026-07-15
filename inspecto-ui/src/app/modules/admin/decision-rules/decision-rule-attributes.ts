import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Decision Rule kind's scalar attribute declarations (C3) — drives `<inspecto-schema-form>` in
 * {@link DecisionRuleFormDialog}. The when-clause (condition tree) and the consequences list are
 * genuinely bespoke sections hand-built in the dialog (the job-form params precedent).
 */
export const DECISION_RULE_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'name', label: 'Rule id', type: 'string', tier: 'required',
        pattern: '[A-Za-z0-9][A-Za-z0-9._-]*',
        placeholder: 'e.g. route_emea_traffic',
        help: 'Letters, digits, dot, dash, underscore; start alphanumeric.',
    },
    { key: 'description', label: 'Description', type: 'string', tier: 'required', required: false },
    {
        key: 'targetType', label: 'Attach to', type: 'select', tier: 'required', default: 'pipeline',
        options: [
            { value: 'pipeline', label: 'Pipeline' },
            { value: 'job', label: 'Job' },
        ],
    },
    {
        key: 'target', label: 'Target', type: 'autocomplete', tier: 'required',
        placeholder: 'e.g. cdr_ingest',
        help: 'The pipeline or job whose records this rule routes.',
    },
    {
        key: 'priority', label: 'Priority', type: 'number', tier: 'optional', default: 100, min: 1, max: 10_000,
        help: 'Lower fires first when several rules target the same records.',
    },
    { key: 'enabled', label: 'Enabled', type: 'boolean', tier: 'optional', default: true },
];
