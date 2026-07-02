import type { AttributeSpec } from 'app/inspecto/component-model';

/**
 * Per-node-type config attribute schemas for the generic {@link NodeConfigDialog} — the non-parser
 * counterpart to `parser-types.ts` (parsers have their own rich dialog). Each authored node type
 * (collector / transform / sink) declares its config attributes with a disclosure tier
 * (`required | optional | advanced`), so the shared `<inspecto-schema-form>` renders a right-sized
 * form instead of the old free-form key/value grid (review R2/R4:
 * `docs/superpower/reviews/node-config.md`).
 *
 * <p>**Best-guess** shapes: authored-flow node config isn't firmly specced server-side (the shapes live
 * in test strings today — see `docs/FEATURE_INVENTORY.md` §G), so these mirror the seeded sample
 * pipelines + the flow-graph design doc. A node type absent from this map (plugins, `transform.record`,
 * anything new) has **no** schema and falls back to the dialog's free-form key/value editor — and every
 * type keeps that editor as a collapsed "Additional config" escape hatch for keys outside its schema.
 */
const NODE_ATTRIBUTES: Record<string, AttributeSpec[]> = {
    'collector.file': [
        { key: 'include', label: 'Include pattern', type: 'string', tier: 'required', placeholder: 'glob:**/*.csv.gz', help: 'Glob (or regex:) selecting files to collect.' },
        { key: 'exclude', label: 'Exclude pattern', type: 'string', tier: 'optional' },
        { key: 'recursive', label: 'Recurse subdirectories', type: 'boolean', tier: 'optional', default: true },
        { key: 'min_age_seconds', label: 'Min file age (s)', type: 'number', tier: 'advanced', min: 0, help: 'Skip files modified more recently than this (stability gate).' },
    ],
    'collector.database': [
        { key: 'query', label: 'SQL query', type: 'multiline', tier: 'required', placeholder: 'SELECT * FROM subscribers WHERE updated_at > :watermark' },
        { key: 'watermark_column', label: 'Watermark column', type: 'string', tier: 'optional', help: 'Monotonic column for incremental extraction.' },
        { key: 'fetch_size', label: 'Fetch size', type: 'number', tier: 'advanced', min: 1, default: 5000 },
    ],
    'collector.stream': [
        { key: 'topic', label: 'Topic / source', type: 'string', tier: 'required' },
        { key: 'group_id', label: 'Consumer group', type: 'string', tier: 'optional' },
        { key: 'batch_size', label: 'Micro-batch size', type: 'number', tier: 'advanced', min: 1, default: 1000 },
    ],
    'transform.filter': [
        { key: 'predicate', label: 'Keep-when predicate', type: 'string', tier: 'required', placeholder: "amount > 0", help: 'Rows matching are kept; the rest go to the dropped branch.' },
    ],
    'transform.route': [
        { key: 'mode', label: 'Route mode', type: 'select', tier: 'required', default: 'case', options: [{ value: 'case', label: 'case (exclusive)' }, { value: 'clone', label: 'clone (fan-out)' }] },
        { key: 'route_column', label: 'Route by column', type: 'string', tier: 'optional', help: 'Named routes are edited on the canvas edges.' },
    ],
    'transform.aggregate': [
        { key: 'group_by', label: 'Group by (columns)', type: 'string', tier: 'required', placeholder: 'region, event_date', help: 'Comma-separated grouping columns.' },
        { key: 'aggregations', label: 'Aggregations (SQL)', type: 'multiline', tier: 'required', placeholder: 'SUM(amount) AS revenue, COUNT(*) AS n' },
    ],
    'transform.alert': [
        { key: 'condition', label: 'Alert condition', type: 'string', tier: 'required', placeholder: 'error_rate > 0.05' },
        { key: 'severity', label: 'Severity', type: 'select', tier: 'required', default: 'WARNING', options: [{ value: 'INFO', label: 'Info' }, { value: 'WARNING', label: 'Warning' }, { value: 'CRITICAL', label: 'Critical' }] },
    ],
    'sink.file': [
        { key: 'format', label: 'Format', type: 'select', tier: 'required', default: 'PARQUET', options: [{ value: 'PARQUET', label: 'Parquet' }, { value: 'CSV', label: 'CSV' }] },
        { key: 'partition_by', label: 'Partition by', type: 'string', tier: 'optional', placeholder: 'event_date', help: 'Column(s) to Hive-partition the output by.' },
        { key: 'compression', label: 'Compression', type: 'select', tier: 'advanced', default: 'snappy', options: [{ value: 'snappy', label: 'snappy' }, { value: 'zstd', label: 'zstd' }, { value: 'gzip', label: 'gzip' }, { value: 'none', label: 'none' }] },
    ],
    'sink.database': [
        { key: 'table', label: 'Target table', type: 'string', tier: 'required' },
        { key: 'mode', label: 'Write mode', type: 'select', tier: 'required', default: 'append', options: [{ value: 'append', label: 'append' }, { value: 'upsert', label: 'upsert' }, { value: 'replace', label: 'replace' }] },
        { key: 'key_columns', label: 'Key columns', type: 'string', tier: 'optional', dependsOn: { key: 'mode', equals: 'upsert' }, help: 'Match columns for upsert.' },
    ],
};

/** The declared attribute schema for a node type, or `undefined` when it has none (free-form only). */
export function nodeAttributesFor(type: string | undefined): AttributeSpec[] | undefined {
    return type ? NODE_ATTRIBUTES[type] : undefined;
}
