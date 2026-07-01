import { HttpEvent, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../../environments/environment';

/**
 * DEMO-ONLY mock for every endpoint not already covered by the feature-specific mock interceptors
 * (connection, flow, ops, studio, jobs). Provides realistic seed data so the full UI works offline
 * for stakeholder demos. Gated on {@code environment.mockDemo}.
 */

const LATENCY_MS = 150;
const NOW = Date.now();
const PIPELINES = ['cdr_ingest', 'subscriber_load', 'voucher_etl', 'billing_daily', 'fraud_events'];

// ── health / status / report ────────────────────────────────────────────────

const READY = { status: 'READY', pipelines: PIPELINES.length };

const PIPELINE_STATUSES = PIPELINES.map((name, i) => ({
    pipeline: name,
    paused: i === 3,
    committedBatches: 120 + i * 37,
    quarantineFiles: i % 3 === 0 ? 2 : 0,
    lastBatchId: `batch-${1000 + i}`,
    lastBatchStatus: i === 4 ? 'FAILED' : 'COMMITTED',
    lastBatchTime: new Date(NOW - i * 3_600_000).toISOString(),
}));

const STATUS_REPORT = {
    generatedAt: new Date(NOW).toISOString(),
    pipelineCount: PIPELINES.length,
    pausedCount: 1,
    totalCommittedBatches: PIPELINE_STATUSES.reduce((s, p) => s + p.committedBatches, 0),
    totalQuarantineFiles: PIPELINE_STATUSES.reduce((s, p) => s + p.quarantineFiles, 0),
    pipelines: PIPELINE_STATUSES,
};

const SERVICE_REPORT = {
    generatedAt: new Date(NOW).toISOString(),
    totalBatches: 847,
    success: 831,
    failed: 16,
    errorRate: 0.019,
    totalOutputRows: 2_847_000,
    p50DurationMs: 1200,
    p95DurationMs: 4800,
    p99DurationMs: 9500,
    windowFrom: new Date(NOW - 7 * 86_400_000).toISOString(),
    windowTo: new Date(NOW).toISOString(),
    pipelines: PIPELINES.map((name, i) => ({
        pipeline: name,
        totalBatches: 150 + i * 20,
        success: 145 + i * 19,
        failed: 5 + (i % 3),
        errorRate: 0.01 + i * 0.005,
        totalOutputRows: 500_000 + i * 100_000,
        p50DurationMs: 800 + i * 200,
        p95DurationMs: 3000 + i * 500,
        p99DurationMs: 7000 + i * 800,
        windowFrom: new Date(NOW - 7 * 86_400_000).toISOString(),
        windowTo: new Date(NOW).toISOString(),
    })),
};

const METRICS_TEXT = `# HELP inspecto_batches_total Total batches processed
# TYPE inspecto_batches_total counter
inspecto_batches_total{pipeline="cdr_ingest"} 257
inspecto_batches_total{pipeline="subscriber_load"} 190
inspecto_batches_total{pipeline="voucher_etl"} 148
inspecto_batches_total{pipeline="billing_daily"} 132
inspecto_batches_total{pipeline="fraud_events"} 120
# HELP inspecto_errors_total Total errors
# TYPE inspecto_errors_total counter
inspecto_errors_total{pipeline="cdr_ingest"} 3
inspecto_errors_total{pipeline="fraud_events"} 8
`;

// ── acquisition metrics ─────────────────────────────────────────────────────

const ACQ_METRICS: Record<string, unknown> = {
    inspecto_files_discovered_total: {
        type: 'counter',
        help: 'Files discovered',
        series: [
            { labels: 'connector="sftp"', value: 1247 },
            { labels: 'connector="s3"', value: 834 },
            { labels: 'connector="local"', value: 423 },
        ],
    },
    inspecto_files_downloaded_total: {
        type: 'counter',
        help: 'Files downloaded',
        series: [
            { labels: 'connector="sftp"', value: 1230 },
            { labels: 'connector="s3"', value: 830 },
            { labels: 'connector="local"', value: 423 },
        ],
    },
    inspecto_downloads_failed_total: {
        type: 'counter',
        help: 'Downloads failed',
        series: [
            { labels: 'connector="sftp"', value: 17 },
            { labels: 'connector="s3"', value: 4 },
        ],
    },
    inspecto_watermark_skipped_total: {
        type: 'counter',
        help: 'Watermark skipped',
        series: [{ labels: 'connector="sftp"', value: 52 }],
    },
    inspecto_bytes_transferred_total: {
        type: 'counter',
        help: 'Bytes transferred',
        series: [
            { labels: 'connector="sftp"', value: 4_812_000_000 },
            { labels: 'connector="s3"', value: 2_100_000_000 },
            { labels: 'connector="local"', value: 890_000_000 },
        ],
    },
    inspecto_active_connections: {
        type: 'gauge',
        help: 'Active connections',
        series: [
            { labels: 'connector="sftp"', value: 3 },
            { labels: 'connector="s3"', value: 2 },
        ],
    },
};

// ── sources ─────────────────────────────────────────────────────────────────

const SOURCES = [
    { pipeline: 'cdr_ingest', id: 'sftp_cdr', connector: 'sftp', connection: 'prod-sftp', includes: ['*.csv'], excludes: [], recursiveDepth: 1, duplicateMode: 'checksum', duplicateOnChange: 'reprocess', guarantee: 'at-least-once', incrementalWatermark: 'last_modified', fetchParallel: 4, fetchRateLimit: 0, postAction: 'archive', dbWatermarkCurrent: null },
    { pipeline: 'subscriber_load', id: 's3_subscribers', connector: 's3', connection: 'aws-prod', includes: ['subscribers/*.parquet'], excludes: [], recursiveDepth: 2, duplicateMode: 'name', duplicateOnChange: 'skip', guarantee: 'exactly-once', incrementalWatermark: 'last_modified', fetchParallel: 8, fetchRateLimit: 0, postAction: 'none', dbWatermarkCurrent: null },
    { pipeline: 'voucher_etl', id: 'local_vouchers', connector: 'local', connection: null, includes: ['vouchers/**/*.json'], excludes: ['*.tmp'], recursiveDepth: 3, duplicateMode: 'checksum', duplicateOnChange: 'reprocess', guarantee: 'at-least-once', incrementalWatermark: null, fetchParallel: 1, fetchRateLimit: 0, postAction: 'delete', dbWatermarkCurrent: null },
    { pipeline: 'billing_daily', id: 'db_billing', connector: 'jdbc', connection: 'billing-db', includes: ['billing_events'], excludes: [], recursiveDepth: 0, duplicateMode: 'watermark', duplicateOnChange: 'skip', guarantee: 'exactly-once', incrementalWatermark: 'event_id', fetchParallel: 1, fetchRateLimit: 100, postAction: 'none', dbWatermarkCurrent: '2026-06-28' },
    { pipeline: 'fraud_events', id: 'kafka_fraud', connector: 'kafka', connection: 'kafka-cluster', includes: ['fraud.*'], excludes: [], recursiveDepth: 0, duplicateMode: 'offset', duplicateOnChange: 'skip', guarantee: 'exactly-once', incrementalWatermark: 'offset', fetchParallel: 3, fetchRateLimit: 0, postAction: 'commit', dbWatermarkCurrent: 'offset:12847' },
];

// ── pipelines list + detail ─────────────────────────────────────────────────

const PIPELINE_VIEWS = PIPELINES.map((name, i) => ({
    name,
    configPath: `pipelines/${name}.toon`,
    paused: i === 3,
    committedBatches: 120 + i * 37,
}));

function batches(pipeline: string): Record<string, string>[] {
    return Array.from({ length: 25 }, (_, i) => {
        const ts = NOW - i * 3_600_000;
        return {
            batch_id: `${pipeline}-b${1000 + i}`,
            status: i % 8 === 0 ? 'FAILED' : 'COMMITTED',
            input_files: String(3 + (i % 5)),
            input_rows: String(1200 + ((i * 311) % 8000)),
            output_rows: String(1100 + ((i * 293) % 7500)),
            rejected_files: String(i % 8 === 0 ? 1 : 0),
            duration_ms: String(800 + ((i * 137) % 6000)),
            committed_at: new Date(ts).toISOString(),
        };
    });
}

function files(pipeline: string): Record<string, string>[] {
    return Array.from({ length: 20 }, (_, i) => ({
        file_name: `${pipeline}_${20260601 + i}.csv`,
        batch_id: `${pipeline}-b${1000 + (i % 10)}`,
        status: i % 12 === 0 ? 'QUARANTINED' : 'PROCESSED',
        rows: String(400 + ((i * 71) % 3000)),
        size_bytes: String(48000 + ((i * 997) % 500000)),
        received_at: new Date(NOW - i * 1_800_000).toISOString(),
    }));
}

function lineage(pipeline: string): Record<string, string>[] {
    return Array.from({ length: 15 }, (_, i) => ({
        batch_id: `${pipeline}-b${1000 + i}`,
        source_file: `${pipeline}_${20260601 + i}.csv`,
        output_table: `${pipeline}_output`,
        output_partition: `day=${20260601 + i}`,
        rows_in: String(1200 + ((i * 311) % 5000)),
        rows_out: String(1100 + ((i * 293) % 4500)),
    }));
}

function quarantine(pipeline: string): Record<string, string>[] {
    return Array.from({ length: 4 }, (_, i) => ({
        file_name: `${pipeline}_bad_${i}.csv`,
        batch_id: `${pipeline}-b${990 + i}`,
        reason: ['parse_error', 'schema_mismatch', 'empty_file', 'encoding_error'][i % 4],
        quarantined_at: new Date(NOW - i * 86_400_000).toISOString(),
    }));
}

const INBOX_STATUSES: Record<string, unknown> = {};
PIPELINES.forEach((name, i) => {
    INBOX_STATUSES[name] = {
        pipeline: name,
        inbox: `inboxes/${name}`,
        pending: i === 0 ? 3 : 0,
        running: i === 0,
        current: i === 0
            ? { batchId: `${name}-b1025`, file: `${name}_20260629.csv`, index: 2, total: 3, startedAt: new Date(NOW - 5000).toISOString() }
            : null,
    };
});

// ── notifications ───────────────────────────────────────────────────────────

const NOTIFICATIONS = Array.from({ length: 8 }, (_, i) => {
    const cats = ['PIPELINE', 'JOB', 'OPS', 'SECURITY', 'PIPELINE'];
    const titles = [
        'Pipeline cdr_ingest batch failed',
        'Job subscriber_rollup completed',
        'High error rate on fraud_events',
        'Unauthorized route access attempt',
        'File quarantined in voucher_etl',
        'Job billing_report succeeded',
        'SLA breach on subscriber_load',
        'Pipeline fraud_events recovered',
    ];
    const bodies = [
        'Batch cdr_ingest-b1008 failed: 1 file rejected (parse_error). 0 of 1200 rows committed.',
        'Job subscriber_rollup finished in 4.2s. 18,400 rows processed.',
        'Error rate on fraud_events exceeded 5% threshold (currently 7.3%) over the last 15 minutes.',
        'POST /runs/unknown/trigger returned 404. Actor: appUser, IP: 192.168.1.42.',
        'File voucher_bad_0.csv quarantined in voucher_etl: schema_mismatch.',
        'Job billing_report completed successfully. 3 output files, 24,000 rows.',
        'Pipeline subscriber_load has not committed a batch in over 2 hours (SLA: 1h).',
        'Pipeline fraud_events resumed normal operation. Error rate dropped to 1.2%.',
    ];
    const ts = NOW - i * 1_800_000;
    return {
        id: `notif-${100 + i}`,
        ts,
        timestamp: new Date(ts).toISOString(),
        category: cats[i % cats.length],
        sourceType: i % 2 === 0 ? 'BATCH_FAILED' : 'JOB_SUCCEEDED',
        sourceId: PIPELINES[i % PIPELINES.length],
        title: titles[i],
        body: bodies[i],
        state: i < 3 ? 'UNREAD' : 'READ',
        readAt: i < 3 ? null : ts + 600_000,
    };
});

const NOTIFICATION_PREFS = [
    { category: 'PIPELINE', label: 'Pipeline', critical: false, available: true, channels: { inApp: true, email: false } },
    { category: 'JOB', label: 'Job', critical: false, available: true, channels: { inApp: true, email: false } },
    { category: 'OPS', label: 'Operations', critical: false, available: true, channels: { inApp: true, email: false } },
    { category: 'COLLABORATION', label: 'Collaboration', critical: false, available: false, channels: { inApp: false, email: false } },
    { category: 'SECURITY', label: 'Security', critical: true, available: true, channels: { inApp: true, email: true } },
];

// ── catalog ─────────────────────────────────────────────────────────────────

const CATALOG_TABLES = [
    { id: 'cdr_output', kind: 'TABLE', label: 'cdr_output', description: { text: 'CDR records after parsing and enrichment', source: 'schema' }, overlay: { lastSeen: new Date(NOW - 3_600_000).toISOString(), rowCount: 847_000, freshness: 'FRESH' } },
    { id: 'subscriber_master', kind: 'TABLE', label: 'subscriber_master', description: { text: 'Subscriber master dimension table', source: 'schema' }, overlay: { lastSeen: new Date(NOW - 7_200_000).toISOString(), rowCount: 125_000, freshness: 'FRESH' } },
    { id: 'voucher_transactions', kind: 'TABLE', label: 'voucher_transactions', description: { text: 'Voucher recharge transactions', source: 'schema' }, overlay: { lastSeen: new Date(NOW - 14_400_000).toISOString(), rowCount: 340_000, freshness: 'FRESH' } },
    { id: 'billing_events', kind: 'TABLE', label: 'billing_events', description: { text: 'Daily billing event feed', source: 'schema' }, overlay: { lastSeen: new Date(NOW - 86_400_000).toISOString(), rowCount: 1_200_000, freshness: 'STALE' } },
    { id: 'fraud_scores', kind: 'TABLE', label: 'fraud_scores', description: { text: 'Real-time fraud scoring output', source: 'schema' }, overlay: { lastSeen: new Date(NOW - 600_000).toISOString(), rowCount: 95_000, freshness: 'FRESH' } },
];

// A Stream is the Catalog's data-origin lens over a Source (+ its Connection) — same identity, catalog view.
const PIPELINE_OUTPUT_TABLE: Record<string, string> = {
    cdr_ingest: 'cdr_output',
    subscriber_load: 'subscriber_master',
    voucher_etl: 'voucher_transactions',
    billing_daily: 'billing_events',
    fraud_events: 'fraud_scores',
};

const CATALOG_STREAMS = SOURCES.map((s) => ({
    id: s.id,
    kind: 'SOURCE',
    label: s.id,
    description: { text: `${s.connector} source feeding ${s.pipeline}`, source: 'source' },
    attrs: { connector: s.connector, connection: s.connection, pipeline: s.pipeline },
}));

const CATALOG_EDGES = CATALOG_STREAMS
    .filter((s) => PIPELINE_OUTPUT_TABLE[s.attrs.pipeline])
    .map((s) => ({ from: s.id, to: PIPELINE_OUTPUT_TABLE[s.attrs.pipeline], kind: 'EMITS' }));

const CATALOG_KPIS = {
    domain: 'telecom',
    kpis: [
        { id: 'arpu', name: 'ARPU', definition: 'Average Revenue Per User = total_revenue / active_subscribers', grain: 'monthly', joinKeys: ['subscriber_id'], inputs: ['billing_events', 'subscriber_master'] },
        { id: 'churn_rate', name: 'Churn Rate', definition: 'Subscribers lost / total subscribers in period', grain: 'monthly', joinKeys: ['subscriber_id'], inputs: ['subscriber_master'] },
        { id: 'fraud_rate', name: 'Fraud Detection Rate', definition: 'Flagged transactions / total transactions', grain: 'daily', joinKeys: ['transaction_id'], inputs: ['fraud_scores', 'cdr_output'] },
        { id: 'recharge_volume', name: 'Recharge Volume', definition: 'Sum of voucher recharge amounts', grain: 'daily', joinKeys: ['subscriber_id'], inputs: ['voucher_transactions'] },
    ],
};

// ── diagnoses ───────────────────────────────────────────────────────────────

const DIAGNOSES = Array.from({ length: 10 }, (_, i) => ({
    batchId: `${PIPELINES[i % PIPELINES.length]}-b${990 + i}`,
    pipeline: PIPELINES[i % PIPELINES.length],
    severity: ['INFO', 'WARNING', 'CRITICAL'][i % 3],
    rootCause: [
        'Schema mismatch: expected 12 columns, found 11 — likely a truncated export.',
        'Encoding error: file contains non-UTF-8 bytes at offset 4821.',
        'Parse failure: malformed CSV at row 347 — unescaped delimiter in quoted field.',
        'Empty file received — upstream export may have failed silently.',
        'Timestamp column "event_ts" contains future dates (>24h ahead), likely timezone misconfiguration.',
    ][i % 5],
    suggestedAlertRuleToon: i % 3 === 2 ? 'alert.schema_mismatch { metric: "rejected_files", comparator: "gt", threshold: 0, window: "1h" }' : null,
    heuristicOnly: i % 4 === 0,
    epochMillis: NOW - i * 7_200_000,
    citations: [{ source: 'batch-audit', ref: `${PIPELINES[i % PIPELINES.length]}-b${990 + i}` }],
}));

// ── config specs ────────────────────────────────────────────────────────────

const CONFIG_SPECS: Record<string, unknown> = {
    pipeline: {
        type: 'pipeline',
        fields: [
            { path: 'pipeline', type: 'STRING', required: true, description: 'Pipeline name (unique identifier)' },
            { path: 'source.connector', type: 'STRING', required: true, description: 'Source connector type', options: ['sftp', 's3', 'local', 'jdbc', 'kafka'] },
            { path: 'source.connection', type: 'STRING', required: false, description: 'Connection profile reference' },
            { path: 'source.includes', type: 'ARRAY', required: true, description: 'File include globs' },
            { path: 'source.excludes', type: 'ARRAY', required: false, description: 'File exclude globs' },
            { path: 'parser.format', type: 'STRING', required: true, description: 'Input format', options: ['csv', 'json', 'parquet', 'avro', 'xml', 'fixed', 'asn1', 'edi', 'custom'] },
            { path: 'parser.delimiter', type: 'STRING', required: false, description: 'Field delimiter (CSV)' },
            { path: 'parser.header', type: 'BOOLEAN', required: false, description: 'First row is header', default: true },
            { path: 'output.format', type: 'STRING', required: true, description: 'Output format', options: ['parquet', 'csv', 'json'] },
            { path: 'output.partitionBy', type: 'ARRAY', required: false, description: 'Partition columns' },
            { path: 'batch.maxFiles', type: 'INTEGER', required: false, description: 'Max files per batch', default: 100, minValue: 1, maxValue: 10000 },
        ],
        rules: [
            { description: 'JDBC connector requires a connection profile', affectedFields: ['source.connector', 'source.connection'], condition: 'source.connector == "jdbc" => source.connection != null' },
        ],
    },
};

// ── route matching & dispatch ───────────────────────────────────────────────

const HEALTH = /\/health$/;
const READY_RE = /\/ready$/;
const STATUS = /\/status$/;
const REPORT = /\/report$/;
const METRICS = /\/metrics$/;
const METRICS_ACQ = /\/metrics\/acquisition$/;
const SOURCES_RE = /\/sources$/;
const PIPELINES_LIST = /\/runs$/;
const PIPELINE_BATCHES = /\/runs\/([^/]+)\/batches$/;
const PIPELINE_FILES = /\/runs\/([^/]+)\/files$/;
const PIPELINE_LINEAGE = /\/runs\/([^/]+)\/lineage$/;
const PIPELINE_QUARANTINE = /\/runs\/([^/]+)\/quarantine$/;
const PIPELINE_PENDING = /\/runs\/([^/]+)\/pending$/;
const PIPELINE_REPORT = /\/runs\/([^/]+)\/report$/;
const PIPELINE_COMMITS = /\/runs\/([^/]+)\/commits$/;
const PIPELINE_TRIGGER = /\/runs\/([^/]+)\/trigger$/;
const PIPELINE_PAUSE = /\/runs\/([^/]+)\/pause$/;
const PIPELINE_RESUME = /\/runs\/([^/]+)\/resume$/;
const NOTIF_LIST = /\/notifications$/;
const NOTIF_UNREAD = /\/notifications\/unread-count$/;
const NOTIF_STREAM = /\/notifications\/stream$/;
const NOTIF_READ_ALL = /\/notifications\/read-all$/;
const NOTIF_READ = /\/notifications\/([^/]+)\/read$/;
const NOTIF_DELETE = /\/notifications\/([^/]+)$/;
const NOTIF_PREFS = /\/notifications\/preferences$/;
const CATALOG_TABLES_RE = /\/catalog$/;
const CATALOG_KPIS_RE = /\/catalog\/kpis$/;
const CATALOG_STREAMS_RE = /\/catalog\/streams$/;
const CATALOG_NODE = /\/catalog\/tables\/([^/]+)$/;
const CATALOG_GRAPH = /\/catalog\/graph$/;
const DIAGNOSES_RE = /\/diagnoses$/;
const CONFIG_SPEC = /\/config\/spec\/([^/]+)$/;
const VALIDATE = /\/validate$/;

export const demoMockInterceptor: HttpInterceptorFn = (req, next) => {
    if (!(environment as { mockDemo?: boolean }).mockDemo) return next(req);

    let m: RegExpMatchArray | null;

    // ── health / status / report ──
    if (req.method === 'GET' && HEALTH.test(req.url)) return reply({ status: 'UP' });
    if (req.method === 'GET' && READY_RE.test(req.url)) return reply(READY);
    if (req.method === 'GET' && STATUS.test(req.url)) return reply(STATUS_REPORT);
    if (req.method === 'GET' && REPORT.test(req.url)) return reply(SERVICE_REPORT);
    if (req.method === 'GET' && METRICS.test(req.url) && !METRICS_ACQ.test(req.url))
        return reply(METRICS_TEXT);
    if (req.method === 'GET' && METRICS_ACQ.test(req.url)) return reply(ACQ_METRICS);

    // ── sources ──
    if (req.method === 'GET' && SOURCES_RE.test(req.url)) return reply(SOURCES);

    // ── pipelines ──
    if (req.method === 'GET' && PIPELINES_LIST.test(req.url)) return reply(PIPELINE_VIEWS);
    if (req.method === 'GET' && (m = req.url.match(PIPELINE_BATCHES))) return reply(batches(decodeURIComponent(m[1])));
    if (req.method === 'GET' && (m = req.url.match(PIPELINE_FILES))) return reply(files(decodeURIComponent(m[1])));
    if (req.method === 'GET' && (m = req.url.match(PIPELINE_LINEAGE))) return reply(lineage(decodeURIComponent(m[1])));
    if (req.method === 'GET' && (m = req.url.match(PIPELINE_QUARANTINE))) return reply(quarantine(decodeURIComponent(m[1])));
    if (req.method === 'GET' && (m = req.url.match(PIPELINE_PENDING))) {
        const name = decodeURIComponent(m[1]);
        return reply(INBOX_STATUSES[name] ?? { pipeline: name, inbox: `inboxes/${name}`, pending: 0, running: false });
    }
    if (req.method === 'GET' && (m = req.url.match(PIPELINE_REPORT))) {
        const name = decodeURIComponent(m[1]);
        const pr = SERVICE_REPORT.pipelines.find((p) => p.pipeline === name) ?? SERVICE_REPORT.pipelines[0];
        return reply(pr);
    }
    if (req.method === 'GET' && (m = req.url.match(PIPELINE_COMMITS))) return reply(['batch-1000', 'batch-1001', 'batch-1002']);
    if (req.method === 'POST' && (m = req.url.match(PIPELINE_TRIGGER)))
        return reply({ total: 3, failed: 0, status: 'triggered' });
    if (req.method === 'POST' && (m = req.url.match(PIPELINE_PAUSE)))
        return reply({ pipeline: decodeURIComponent(m[1]), paused: true });
    if (req.method === 'POST' && (m = req.url.match(PIPELINE_RESUME)))
        return reply({ pipeline: decodeURIComponent(m[1]), paused: false });

    // ── notifications ──
    if (req.method === 'GET' && NOTIF_STREAM.test(req.url)) return next(req); // SSE — let it pass (no server = silent fail)
    if (req.method === 'GET' && NOTIF_UNREAD.test(req.url))
        return reply({ count: NOTIFICATIONS.filter((n) => n.state === 'UNREAD').length });
    if (req.method === 'GET' && NOTIF_LIST.test(req.url)) return reply(NOTIFICATIONS);
    if (req.method === 'POST' && NOTIF_READ_ALL.test(req.url)) return reply({ updated: NOTIFICATIONS.length });
    if (req.method === 'POST' && (m = req.url.match(NOTIF_READ))) return reply({ ok: true });
    if (req.method === 'DELETE' && (m = req.url.match(NOTIF_DELETE))) return reply({ ok: true });
    if (req.method === 'GET' && NOTIF_PREFS.test(req.url)) return reply(NOTIFICATION_PREFS);
    if (req.method === 'PUT' && NOTIF_PREFS.test(req.url)) return reply(NOTIFICATION_PREFS);

    // ── catalog ──
    if (req.method === 'GET' && CATALOG_KPIS_RE.test(req.url)) return reply(CATALOG_KPIS);
    if (req.method === 'GET' && CATALOG_STREAMS_RE.test(req.url)) return reply(CATALOG_STREAMS);
    if (req.method === 'GET' && CATALOG_TABLES_RE.test(req.url)) return reply(CATALOG_TABLES);
    if (req.method === 'GET' && (m = req.url.match(CATALOG_NODE))) {
        const id = decodeURIComponent(m[1]);
        const all = [...CATALOG_TABLES, ...CATALOG_STREAMS];
        const node = all.find((t) => t.id === id) ?? all[0];
        const edges = CATALOG_EDGES.filter((e) => e.from === id || e.to === id);
        const neighborIds = new Set(edges.flatMap((e) => [e.from, e.to]));
        const nodes = all.filter((n) => neighborIds.has(n.id) && n.id !== id);
        return reply({ node, neighbors: { nodes, edges } });
    }
    if (req.method === 'GET' && CATALOG_GRAPH.test(req.url))
        return reply({ nodes: [...CATALOG_TABLES, ...CATALOG_STREAMS], edges: CATALOG_EDGES });

    // ── diagnoses ──
    if (req.method === 'GET' && DIAGNOSES_RE.test(req.url)) return reply(DIAGNOSES);

    // ── config ──
    if (req.method === 'GET' && (m = req.url.match(CONFIG_SPEC))) {
        const type = decodeURIComponent(m[1]);
        return reply(CONFIG_SPECS[type] ?? CONFIG_SPECS['pipeline']);
    }
    if (req.method === 'POST' && VALIDATE.test(req.url))
        return reply({ clean: true, findings: [], warnings: [] });

    return next(req);
};

function reply<T>(body: T): Observable<HttpEvent<unknown>> {
    return of(new HttpResponse({ status: 200, body })).pipe(delay(LATENCY_MS));
}
