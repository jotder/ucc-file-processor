import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The Dataset & Go-live stage's `output:` block — Stage-1's file format/compression only; naming
 * and activation are handled separately (identity is the pipeline name; go-live flips `active`).
 * Keys are relative to the block root (the pane wraps the result as `{output: ...}`, matching
 * the collector/parsing panes' own convention).
 */
export const PUBLISH_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'format', label: 'Output format', type: 'select', tier: 'required', required: false, default: 'PARQUET',
        options: [
            { value: 'PARQUET', label: 'Parquet' },
            { value: 'CSV', label: 'CSV' },
        ],
        help: 'Stage-1 output file format.',
    },
    {
        key: 'compression', label: 'Compression', type: 'string', tier: 'optional',
        placeholder: 'snappy', help: 'Codec for the output; blank = format default.',
    },
];
