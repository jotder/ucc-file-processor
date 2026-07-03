import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Expectation kind's attribute declarations (C2) — drives `<inspecto-schema-form>` in
 * {@link ExpectationFormDialog}. Identity + target + check kind are required; the kind-specific
 * parameters appear via `dependsOn`; severity/arming are optional (collapsed).
 */
export const EXPECTATION_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'name', label: 'Expectation id', type: 'string', tier: 'required',
        pattern: '[A-Za-z0-9][A-Za-z0-9._-]*',
        placeholder: 'e.g. cdr_msisdn_not_null',
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
        key: 'target', label: 'Target', type: 'string', tier: 'required',
        placeholder: 'e.g. cdr_ingest',
        help: 'The pipeline or job whose records this check validates.',
    },
    {
        key: 'column', label: 'Column', type: 'string', tier: 'required',
        placeholder: 'e.g. msisdn',
        help: 'The record field the check inspects.',
    },
    {
        key: 'kind', label: 'Check', type: 'select', tier: 'required', default: 'non_null',
        options: [
            { value: 'non_null', label: 'Non-null' },
            { value: 'range', label: 'Range' },
            { value: 'regex', label: 'Regex' },
            { value: 'referential', label: 'Referential' },
        ],
    },
    {
        key: 'min', label: 'Minimum', type: 'number', tier: 'required',
        dependsOn: { key: 'kind', equals: 'range' },
    },
    {
        key: 'max', label: 'Maximum', type: 'number', tier: 'required',
        dependsOn: { key: 'kind', equals: 'range' },
    },
    {
        key: 'pattern', label: 'Pattern (regex)', type: 'string', tier: 'required',
        dependsOn: { key: 'kind', equals: 'regex' },
        placeholder: '^\\+?[1-9]\\d{6,14}$',
        help: 'Values must fully match this regular expression.',
    },
    {
        key: 'refDataset', label: 'Reference dataset', type: 'string', tier: 'required',
        dependsOn: { key: 'kind', equals: 'referential' },
        placeholder: 'e.g. tariff_ref',
    },
    {
        key: 'refColumn', label: 'Reference column', type: 'string', tier: 'required',
        dependsOn: { key: 'kind', equals: 'referential' },
        placeholder: 'e.g. code',
    },
    {
        key: 'severity', label: 'Severity of a failure', type: 'select', tier: 'optional', default: 'MAJOR',
        options: [
            { value: 'CRITICAL', label: 'CRITICAL' },
            { value: 'MAJOR', label: 'MAJOR' },
            { value: 'MINOR', label: 'MINOR' },
        ],
        help: 'Severity of the Incident raised when the check fails.',
    },
    { key: 'enabled', label: 'Enabled (evaluated in sweeps)', type: 'boolean', tier: 'optional', default: true },
];
