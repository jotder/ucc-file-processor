import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The authored notification-rule kind's attribute declarations — drives `<inspecto-schema-form>` in
 * {@link RuleFormDialog}. Event type + category are the trigger/routing; the three `{{var}}` templates
 * are optional (the server defaults them); an authored rule is checked ahead of the built-in defaults,
 * so a rule for an already-covered event type overrides the built-in's copy.
 */
export const RULE_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'id', label: 'Rule id', type: 'string', tier: 'required',
        pattern: '[A-Za-z0-9][A-Za-z0-9._-]*',
        placeholder: 'e.g. custom_batch_failed',
        help: 'Letters, digits, dot, dash, underscore; start alphanumeric.',
    },
    {
        key: 'eventType', label: 'Event type', type: 'string', tier: 'required',
        placeholder: 'e.g. BATCH_FAILED or job.custom',
        help: 'The event this rule fires on (case-insensitive). An authored rule overrides the built-in for the same type.',
    },
    {
        key: 'minLevel', label: 'Minimum severity', type: 'select', tier: 'optional', default: '',
        options: [
            { value: '', label: 'Any' },
            { value: 'INFO', label: 'Info' },
            { value: 'WARN', label: 'Warning' },
            { value: 'ERROR', label: 'Error' },
        ],
        help: 'Events below this severity do not fire the rule.',
    },
    {
        key: 'category', label: 'Category', type: 'select', tier: 'required', default: 'ops',
        options: [
            { value: 'pipeline', label: 'Pipeline' },
            { value: 'ops', label: 'Operations' },
            { value: 'job', label: 'Jobs' },
        ],
        help: 'The preference key gating delivery (the Preferences tab).',
    },
    {
        key: 'titleTemplate', label: 'Title template', type: 'multiline', tier: 'optional',
        placeholder: '{{type}}',
        help: '{{var}} interpolation over the event: type, level, pipeline, message, attributes.*, payload.*, time.',
    },
    {
        key: 'bodyTemplate', label: 'Body template', type: 'multiline', tier: 'optional',
        placeholder: '{{message}}',
    },
    {
        key: 'dedupeKeyTemplate', label: 'Dedupe key template', type: 'multiline', tier: 'advanced',
        placeholder: '{{type}}:{{correlationId}}',
        help: 'Identical unread notifications collapse on this rendered key.',
    },
    { key: 'enabled', label: 'Enabled', type: 'boolean', tier: 'optional', default: true },
];
