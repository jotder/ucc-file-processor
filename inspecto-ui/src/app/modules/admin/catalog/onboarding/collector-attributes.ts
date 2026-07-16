import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Collection stage's schema-form specs — flat keys (`__` path separator, see
 * `onboarding-config-utils`) over the Stage-1 `collector:` TOON block (see
 * `PipelineConfigParser`; every key here is engine-real). A pipeline with no `collector:`
 * block reads its local inbox (`dirs.poll`) — exactly what the defaults describe.
 */
export const COLLECTOR_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'connector',
        label: 'Connector',
        type: 'select',
        tier: 'required',
        default: 'local',
        options: [
            { value: 'local', label: 'Local folder' },
            { value: 'sftp', label: 'SFTP' },
            { value: 'azure', label: 'Azure Blob' },
            { value: 'kafka', label: 'Kafka' },
            { value: 'db', label: 'Database' },
        ],
        help: 'Where files are collected from. Non-local connectors need the connectors module in this build.',
    },
    {
        key: 'connection',
        label: 'Connection',
        type: 'autocomplete',
        tier: 'optional',
        help: 'Saved Connection profile id — not needed for a local folder.',
    },
    {
        key: 'include',
        label: 'Include patterns',
        type: 'string',
        tier: 'optional',
        placeholder: '*.csv, orders_*.txt',
        help: 'Glob/regex discovery patterns; comma-separate multiple. Blank = the pipeline file pattern.',
    },
    {
        key: 'discovery',
        label: 'Discovery',
        type: 'select',
        tier: 'optional',
        default: 'poll',
        options: [
            { value: 'poll', label: 'Poll' },
            { value: 'watch', label: 'Watch (filesystem events)' },
        ],
    },
    {
        key: 'duplicate__mode',
        label: 'Duplicate detection',
        type: 'select',
        tier: 'optional',
        default: 'path',
        options: [
            { value: 'path', label: 'By path' },
            { value: 'metadata', label: 'By metadata (size + mtime)' },
            { value: 'checksum', label: 'By checksum' },
            { value: 'etag', label: 'By remote ETag' },
        ],
        help: 'File-level duplicate policy — how a re-seen file is recognised.',
    },
    {
        key: 'post_action__on_success',
        label: 'After success',
        type: 'select',
        tier: 'optional',
        default: 'RETAIN',
        options: [
            { value: 'RETAIN', label: 'Retain' },
            { value: 'DELETE', label: 'Delete' },
            { value: 'MOVE', label: 'Move to archive' },
            { value: 'RENAME', label: 'Rename' },
            { value: 'TAG', label: 'Tag' },
        ],
    },
    { key: 'exclude', label: 'Exclude patterns', type: 'string', tier: 'advanced', placeholder: '*.tmp' },
    { key: 'recursive_depth', label: 'Recursive depth', type: 'number', tier: 'advanced', min: 0, help: 'Blank = unbounded.' },
    {
        key: 'duplicate__on_change',
        label: 'On changed duplicate',
        type: 'select',
        tier: 'advanced',
        options: [
            { value: 'reprocess', label: 'Reprocess' },
            { value: 'skip', label: 'Skip' },
        ],
        help: 'What to do when a known file re-appears changed.',
    },
    {
        key: 'guarantee',
        label: 'Delivery guarantee',
        type: 'select',
        tier: 'advanced',
        options: [
            { value: 'BEST_EFFORT', label: 'Best effort' },
            { value: 'AT_LEAST_ONCE', label: 'At least once' },
            { value: 'EXACTLY_ONCE', label: 'Exactly once' },
        ],
    },
    { key: 'stability__window', label: 'Stability window', type: 'string', tier: 'advanced', placeholder: '5s', help: 'Wait for a file to stop growing before collecting it.' },
    { key: 'post_action__archive_path', label: 'Archive path', type: 'string', tier: 'advanced', help: 'Target directory when "After success" is Move.' },
];
