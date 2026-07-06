import type { ConnectionProfile } from '../../api/connections.service';
import type { JobDetail } from '../../api/jobs.service';
import type { OperationalObject } from '../../api/objects.service';
import { CONNECTIONS_COLL } from '../handlers/connections.handler';
import { JOBS_COLL, recordRun } from '../handlers/jobs.handler';
import { OPS_OBJECTS_COLL } from '../handlers/ops.handler';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { eventToSignal } from '../../signal/signal';
import { SIGNALS_COLL } from '../signals';
import { MockStore } from '../mock-store';
import { putComponent, seedIconMap } from './seed-utils';

/**
 * **Financial Auditing** Space-Template seed pack (W5) — GL postings vs. bank payments: load both
 * sides from the ERP and the bank export, reconcile keyed on transaction id with a $0.01 tolerance
 * (`gl_entries`/`payments` in SAMPLE_SOURCES carry the deliberate break scenario: one mis-paid
 * amount, one posting never paid, one payment never posted), and track findings as incidents.
 */
export function seedFinancialAudit(store: MockStore, space: string): void {
    const now = Date.now();
    const iso = (offsetMin: number): string => new Date(now + offsetMin * 60_000).toISOString();
    seedIconMap(store, space);

    // ── Workbench ───────────────────────────────────────────────────────────────────────────────
    const connections: ConnectionProfile[] = [
        { id: 'erp_db', connector: 'db', host: 'erp.corp.example', port: 1521, database: 'GL', username: 'audit_ro', password: '${ENV:ERP_DB_PW}', description: 'ERP general-ledger replica (read-only)' },
        { id: 'bank_sftp', connector: 'sftp', host: 'sftp.bank.example', port: 22, basePath: '/statements', username: 'corp', password: '${ENV:BANK_SFTP_PW}', description: 'Bank statement/payment export' },
    ];
    for (const c of connections) store.put(space, CONNECTIONS_COLL, c.id, c);

    putComponent(store, space, 'grammar', 'bank_mt940_csv', { delimiter: ';', has_header: true });
    putComponent(store, space, 'schema', 'gl_posting', {
        fields: [{ name: 'id', type: 'integer' }, { name: 'account', type: 'string' }, { name: 'entry_type', type: 'string' }, { name: 'amount_usd', type: 'decimal' }],
    });
    putComponent(store, space, 'transform', 'drop_reversals', { type: 'transform.filter', where: "entry_type != 'reversal'" });
    putComponent(store, space, 'sink', 'audit_store', { type: 'sink.persistent', format: 'parquet', partitions: ['posting_date'] });

    store.put(space, PIPELINES_COLL, 'gl_load', {
        name: 'gl_load',
        active: true,
        nodes: [
            { id: 'extract', type: 'collector.database', name: 'Extract GL postings', use: 'connections/erp_db' },
            { id: 'clean', type: 'transform.filter', name: 'Drop reversals', config: { predicate: "entry_type != 'reversal'" } },
            { id: 'store', type: 'sink.file', name: 'Audit parquet', config: { format: 'PARQUET', partition_by: 'posting_date' } },
        ],
        edges: [
            { from: 'extract', rel: 'success', to: 'clean' },
            { from: 'clean', rel: 'kept', to: 'store' },
        ],
    });
    store.put(space, PIPELINES_COLL, 'payments_load', {
        name: 'payments_load',
        active: true,
        nodes: [
            { id: 'collect', type: 'collector.file', name: 'Collect bank exports', use: 'connections/bank_sftp', config: { include: 'glob:**/*.csv' } },
            { id: 'parse', type: 'parser.dsv', name: 'Parse statement CSV', config: { delimiter: ';', header: true } },
            { id: 'store', type: 'sink.database', name: 'Payments side' },
        ],
        edges: [
            { from: 'collect', rel: 'success', to: 'parse' },
            { from: 'parse', rel: 'success', to: 'store' },
        ],
    });

    // ── Studio ──────────────────────────────────────────────────────────────────────────────────
    putComponent(store, space, 'dataset', 'gl_entries', {
        name: 'gl_entries', kind: 'virtual', sourceName: 'gl_entries',
        query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
        physicalRef: null,
        columns: [
            { name: 'id', type: 'number', role: 'dimension' },
            { name: 'account', type: 'string', role: 'dimension' },
            { name: 'entry_type', type: 'string', role: 'dimension' },
            { name: 'amount_usd', type: 'number', role: 'measure' },
            { name: 'posted_by', type: 'string', role: 'dimension' },
            { name: 'posting_date', type: 'date', role: 'temporal' },
        ],
        measures: [{ id: 'total_posted', label: 'Total posted', expression: 'sum(amount_usd)' }],
        viz: null,
    });
    putComponent(store, space, 'dataset', 'payments', {
        name: 'payments', kind: 'virtual', sourceName: 'payments',
        query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
        physicalRef: null,
        columns: [
            { name: 'id', type: 'number', role: 'dimension' },
            { name: 'account', type: 'string', role: 'dimension' },
            { name: 'amount_usd', type: 'number', role: 'measure' },
            { name: 'method', type: 'string', role: 'dimension' },
            { name: 'value_date', type: 'date', role: 'temporal' },
        ],
        measures: [], viz: null,
    });
    putComponent(store, space, 'reconciliation', 'gl_vs_payments', {
        name: 'gl_vs_payments',
        leftDataset: 'gl_entries',
        rightDataset: 'payments',
        keyColumns: ['id'],
        compareColumns: [{ column: 'amount_usd', toleranceType: 'absolute', tolerance: 0.01 }],
        breaks: [],
        lastRunAt: null,
    });

    putComponent(store, space, 'widget', 'total_posted', {
        name: 'total_posted', datasetId: 'gl_entries', vizType: 'kpi',
        controls: { value: [{ field: 'amount_usd', agg: 'sum' }] },
        description: 'Total GL postings in the audit window', tags: ['audit'],
    });
    putComponent(store, space, 'widget', 'postings_by_account', {
        name: 'postings_by_account', datasetId: 'gl_entries', vizType: 'bar',
        controls: { x: [{ field: 'account' }], y: [{ field: 'amount_usd', agg: 'sum' }] },
        description: 'Posted amounts per GL account', tags: ['audit'],
    });
    putComponent(store, space, 'widget', 'postings_over_time', {
        name: 'postings_over_time', datasetId: 'gl_entries', vizType: 'line',
        controls: { x: [{ field: 'posting_date', grain: 'day' }], y: [{ field: 'amount_usd', agg: 'sum' }] },
        description: 'Daily posting volume', tags: ['audit'],
    });
    putComponent(store, space, 'dashboard', 'audit_overview', {
        name: 'audit_overview',
        tiles: [
            { widgetId: 'total_posted', span: 1 },
            { widgetId: 'postings_by_account', span: 1 },
            { widgetId: 'postings_over_time', span: 2 },
        ],
        filter: { kind: 'group', op: 'AND', items: [] },
        exposedFields: ['account'],
    });

    // ── Business ────────────────────────────────────────────────────────────────────────────────
    putComponent(store, space, 'requirement', 'gl_to_payments_recon', {
        id: 'gl_to_payments_recon',
        title: 'GL-to-payments reconciliation',
        kind: 'reconciliation',
        description: 'Every GL revenue posting must match a bank payment within $0.01; unmatched items become audit findings.',
        status: 'delivered',
        submittedAt: iso(-60 * 24 * 30),
        decisionNote: 'Accepted — quarterly audit control AC-114.',
        decidedAt: iso(-60 * 24 * 29),
        deliveredNote: 'reconciliation/gl_vs_payments + dashboard/audit_overview',
        deliveredAt: iso(-60 * 24 * 20),
    });

    // ── Ops ─────────────────────────────────────────────────────────────────────────────────────
    store.put<JobDetail>(space, JOBS_COLL, 'gl_load_nightly', {
        name: 'gl_load_nightly', type: 'ingest', cron: '0 0 1 * * *', onPipeline: null, enabled: true,
        lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 11), nextFire: iso(60 * 13), catchUp: true,
        params: { pipeline: 'gl_load' },
    });
    recordRun(store, space, 'gl_load_nightly', 'CRON', 'SUCCESS', now - 11 * 3_600_000, 54_000, 'Loaded 5 postings (sample window).');
    store.put<JobDetail>(space, JOBS_COLL, 'audit_recon_weekly', {
        name: 'audit_recon_weekly', type: 'report', cron: '0 0 7 * * 1', onPipeline: null, enabled: true,
        lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 24 * 2), nextFire: iso(60 * 24 * 5),
        params: { reconciliation: 'gl_vs_payments' },
    });
    recordRun(store, space, 'audit_recon_weekly', 'CRON', 'SUCCESS', now - 48 * 3_600_000, 8_600, '3 open breaks (1 amount, 1 unpaid posting, 1 unposted payment).');

    const auditEvents: Array<[string, string, string, string]> = [
        ['BATCH_COMMITTED', 'INFO', 'gl_load', 'Committed GL postings for 2026-06-24.'],
        ['BATCH_COMMITTED', 'INFO', 'payments_load', 'Parsed 1 bank statement file.'],
        ['JOB_SUCCEEDED', 'INFO', 'gl_load', 'Nightly GL load completed.'],
        ['ALERT_FIRED', 'WARN', 'payments_load', 'Unmatched payment detected in suspense account.'],
    ];
    auditEvents.forEach(([type, level, pipeline, message], i) => {
        const ts = now - i * 2_700_000;
        store.put(space, SIGNALS_COLL, `evt-aud-${i}`, eventToSignal({
            eventId: `evt-aud-${i}`, ts, timestamp: new Date(ts).toISOString(), level, type,
            source: 'engine', pipeline, correlationId: null, message, attributes: {},
        }));
    });

    const findings: Array<[string, string, string, string, string]> = [
        ['INCIDENT', 'Unmatched wire in suspense (txn 4006, $410.00)', 'OPEN', 'WARNING', 'HIGH'],
        ['CASE', 'Manual posting 4003 paid $50 over booked amount', 'IN_PROGRESS', 'CRITICAL', 'HIGH'],
    ];
    findings.forEach(([objectType, title, status, severity, priority], i) => {
        const ts = now - (i + 1) * 7_200_000;
        store.put<OperationalObject>(space, OPS_OBJECTS_COLL, `${objectType.toLowerCase()}-aud-${i}`, {
            id: `${objectType.toLowerCase()}-aud-${i}`, objectType, title,
            description: 'Seeded by the Financial Auditing space template.', status, severity, priority,
            owner: 'audit', assignee: i % 2 ? 'meera' : 'tom', correlationId: null,
            attributes: { pipeline: i ? 'gl_load' : 'payments_load' }, createdAt: ts, updatedAt: ts + 600_000,
            closedAt: 0,
        });
    });
}
