import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Notification-channel kind's attribute declarations (C4) — drives `<inspecto-schema-form>` in
 * {@link ChannelFormDialog}. Kind + target are the whole config; `enabled` is optional (default on).
 * Delivery is mocked end-to-end — the target is recorded on the ledger, never actually contacted.
 */
export const CHANNEL_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'id', label: 'Channel id', type: 'string', tier: 'required',
        pattern: '[A-Za-z0-9][A-Za-z0-9._-]*',
        placeholder: 'e.g. ops_email',
        help: 'Letters, digits, dot, dash, underscore; start alphanumeric.',
    },
    {
        key: 'kind', label: 'Kind', type: 'select', tier: 'required', default: 'EMAIL',
        options: [
            { value: 'EMAIL', label: 'Email' },
            { value: 'WEBHOOK', label: 'Webhook' },
        ],
    },
    {
        key: 'target', label: 'Email address', type: 'string', tier: 'required',
        dependsOn: { key: 'kind', equals: 'EMAIL' },
        placeholder: 'ops@example.com',
        help: 'Where alert/incident notifications are delivered.',
    },
    {
        key: 'targetUrl', label: 'Webhook URL', type: 'string', tier: 'required',
        dependsOn: { key: 'kind', equals: 'WEBHOOK' },
        placeholder: 'https://hooks.example.com/inspecto',
        help: 'POSTed a JSON payload per notification.',
    },
    { key: 'description', label: 'Description', type: 'string', tier: 'optional' },
    { key: 'enabled', label: 'Enabled', type: 'boolean', tier: 'optional', default: true },
];
