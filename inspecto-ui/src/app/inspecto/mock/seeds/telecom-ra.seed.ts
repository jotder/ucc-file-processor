import type { AlertRule, FiredAlert } from '../../api/alerts.service';
import type { ConnectionProfile } from '../../api/connections.service';
import type { EventRow } from '../../api/events.service';
import type { JobDetail } from '../../api/jobs.service';
import type { OperationalObject } from '../../api/objects.service';
import { CONNECTIONS_COLL } from '../handlers/connections.handler';
import { JOBS_COLL, recordRun } from '../handlers/jobs.handler';
import { ALERT_RULES_COLL, EVENTS_COLL, FIRED_ALERTS_COLL, OPS_OBJECTS_COLL } from '../handlers/ops.handler';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { MockStore } from '../mock-store';
import { putComponent, seedIconMap } from './seed-utils';

/**
 * **Telecom Revenue Assurance** Space-Template seed pack (W5) — the switch-vs-billing story:
 * collect CDRs from the switch and rated records from billing, reconcile the two sides (C9's
 * deliberate break scenario over `switch_cdr`/`billing_cdr` in SAMPLE_SOURCES), and surface
 * leakage on an RA dashboard. Ops entities are RA-flavored so every lens has something real.
 */
export function seedTelecomRa(store: MockStore, space: string): void {
    const now = Date.now();
    const iso = (offsetMin: number): string => new Date(now + offsetMin * 60_000).toISOString();
    seedIconMap(store, space);

    // ── Workbench: connections → registry kinds → pipelines ────────────────────────────────────
    const connections: ConnectionProfile[] = [
        { id: 'switch_sftp', connector: 'sftp', host: 'msc1.telco.example', port: 22, basePath: '/cdr/out', username: 'ra', password: '${ENV:SWITCH_SFTP_PW}', description: 'MSC switch CDR drop zone' },
        { id: 'billing_db', connector: 'db', host: 'billing.telco.example', port: 5432, database: 'rated', username: 'ra_ro', password: '${ENV:BILLING_DB_PW}', options: { sslmode: 'require' }, description: 'Billing rated-events replica (read-only)' },
        { id: 'ra_archive', connector: 's3', host: 's3.amazonaws.com', basePath: 'ra-archive', options: { region: 'eu-west-1' }, description: 'Reconciled-batch archive' },
    ];
    for (const c of connections) store.put(space, CONNECTIONS_COLL, c.id, c);

    putComponent(store, space, 'grammar', 'switch_cdr_csv', { delimiter: ',', has_header: true });
    putComponent(store, space, 'schema', 'cdr_record', {
        fields: [{ name: 'id', type: 'integer' }, { name: 'msisdn', type: 'string' }, { name: 'duration_s', type: 'integer' }, { name: 'cost_usd', type: 'decimal' }],
    });
    putComponent(store, space, 'transform', 'drop_zero_duration', { type: 'transform.filter', where: 'duration_s > 0' });
    putComponent(store, space, 'sink', 'cdr_parquet', { type: 'sink.persistent', format: 'parquet', partitions: ['event_date'] });

    store.put(space, PIPELINES_COLL, 'switch_cdr_ingest', {
        name: 'switch_cdr_ingest',
        active: true,
        nodes: [
            { id: 'collect', type: 'collector.file', name: 'Collect switch CDRs', use: 'connections/switch_sftp', config: { include: 'glob:**/*.csv.gz' } },
            { id: 'parse', type: 'parser.dsv', name: 'Parse CDR CSV', config: { delimiter: ',', header: true } },
            { id: 'filter', type: 'transform.filter', name: 'Drop zero-duration', config: { predicate: 'duration_s > 0' } },
            { id: 'write', type: 'sink.file', name: 'Switch CDR parquet', config: { format: 'PARQUET', partition_by: 'event_date' } },
        ],
        edges: [
            { from: 'collect', rel: 'success', to: 'parse' },
            { from: 'parse', rel: 'success', to: 'filter' },
            { from: 'filter', rel: 'kept', to: 'write' },
        ],
    });
    store.put(space, PIPELINES_COLL, 'billing_rated_load', {
        name: 'billing_rated_load',
        active: true,
        nodes: [
            { id: 'extract', type: 'collector.database', name: 'Extract rated events', use: 'connections/billing_db' },
            { id: 'daily', type: 'transform.aggregate', name: 'Daily rated totals' },
            { id: 'load', type: 'sink.database', name: 'Load rated side' },
        ],
        edges: [
            { from: 'extract', rel: 'success', to: 'daily' },
            { from: 'daily', rel: 'success', to: 'load' },
        ],
    });

    // ── Studio: datasets → reconciliation → widgets → dashboard ────────────────────────────────
    const cdrColumns = [
        { name: 'id', type: 'number', role: 'dimension' },
        { name: 'msisdn', type: 'string', role: 'dimension' },
        { name: 'duration_s', type: 'number', role: 'measure' },
        { name: 'cost_usd', type: 'number', role: 'measure' },
        { name: 'event_time', type: 'date', role: 'temporal' },
    ];
    for (const side of ['switch_cdr', 'billing_cdr'] as const) {
        putComponent(store, space, 'dataset', side, {
            name: side, kind: 'virtual', sourceName: side,
            query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
            physicalRef: null, columns: cdrColumns, measures: [], viz: null,
        });
    }
    putComponent(store, space, 'dataset', 'cdr_traffic', {
        name: 'cdr_traffic', kind: 'virtual', sourceName: 'cdr',
        query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
        physicalRef: null,
        columns: [
            { name: 'id', type: 'number', role: 'dimension' },
            { name: 'msisdn', type: 'string', role: 'dimension' },
            { name: 'tariff', type: 'string', role: 'dimension' },
            { name: 'duration_s', type: 'number', role: 'measure' },
            { name: 'bytes_used', type: 'number', role: 'measure' },
            { name: 'cost_usd', type: 'number', role: 'measure' },
            { name: 'event_time', type: 'date', role: 'temporal' },
        ],
        measures: [{ id: 'total_revenue', label: 'Total revenue', expression: 'sum(cost_usd)' }],
        viz: null,
    });
    putComponent(store, space, 'reconciliation', 'switch_vs_billing', {
        name: 'switch_vs_billing',
        leftDataset: 'switch_cdr',
        rightDataset: 'billing_cdr',
        keyColumns: ['id'],
        compareColumns: [{ column: 'cost_usd', toleranceType: 'absolute', tolerance: 0.02 }],
        breaks: [],
        lastRunAt: null,
    });

    putComponent(store, space, 'widget', 'total_revenue', {
        name: 'total_revenue', datasetId: 'cdr_traffic', vizType: 'kpi',
        controls: { value: [{ field: 'cost_usd', agg: 'sum' }] },
        description: 'Rated revenue across the loaded CDR sample', tags: ['ra'],
    });
    putComponent(store, space, 'widget', 'revenue_by_tariff', {
        name: 'revenue_by_tariff', datasetId: 'cdr_traffic', vizType: 'bar',
        controls: { x: [{ field: 'tariff' }], y: [{ field: 'cost_usd', agg: 'sum' }] },
        description: 'Where the money is: rated revenue per tariff plan', tags: ['ra', 'billing'],
    });
    putComponent(store, space, 'widget', 'usage_over_time', {
        name: 'usage_over_time', datasetId: 'cdr_traffic', vizType: 'line',
        controls: { x: [{ field: 'event_time', grain: 'day' }], y: [{ field: 'bytes_used', agg: 'sum' }] },
        description: 'Daily data usage trend', tags: ['ra', 'usage'],
    });
    putComponent(store, space, 'dashboard', 'ra_overview', {
        name: 'ra_overview',
        tiles: [
            { widgetId: 'total_revenue', span: 1 },
            { widgetId: 'revenue_by_tariff', span: 1 },
            { widgetId: 'usage_over_time', span: 2 },
        ],
        filter: { kind: 'group', op: 'AND', items: [] },
        exposedFields: ['tariff'],
    });

    // ── Business: the requirement this template exists to satisfy ──────────────────────────────
    putComponent(store, space, 'requirement', 'daily_switch_vs_billing_recon', {
        id: 'daily_switch_vs_billing_recon',
        title: 'Daily switch vs billing reconciliation',
        kind: 'reconciliation',
        description: 'Compare switch CDRs against billing rated events daily; every unrated or mis-rated call is potential revenue leakage.',
        status: 'delivered',
        submittedAt: iso(-60 * 24 * 14),
        decisionNote: 'Core RA control — accepted.',
        decidedAt: iso(-60 * 24 * 13),
        deliveredNote: 'reconciliation/switch_vs_billing + dashboard/ra_overview',
        deliveredAt: iso(-60 * 24 * 7),
    });

    // ── Ops: jobs + runs, events, alert rules + fired alerts, incidents/cases ──────────────────
    store.put<JobDetail>(space, JOBS_COLL, 'switch_ingest_daily', {
        name: 'switch_ingest_daily', type: 'ingest', cron: '0 0 5 * * *', onPipeline: null, enabled: true,
        lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 7), nextFire: iso(60 * 17), catchUp: true,
        params: { source: 'switch_sftp', pipeline: 'switch_cdr_ingest' },
    });
    recordRun(store, space, 'switch_ingest_daily', 'CRON', 'SUCCESS', now - 7 * 3_600_000, 96_000, 'Ingested 812 CDR files.');
    recordRun(store, space, 'switch_ingest_daily', 'CRON', 'SUCCESS', now - 31 * 3_600_000, 91_400, 'Ingested 798 CDR files.');
    store.put<JobDetail>(space, JOBS_COLL, 'recon_daily', {
        name: 'recon_daily', type: 'report', cron: '0 30 6 * * *', onPipeline: null, enabled: true,
        lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 6), nextFire: iso(60 * 18),
        params: { reconciliation: 'switch_vs_billing' },
    });
    recordRun(store, space, 'recon_daily', 'CRON', 'SUCCESS', now - 6 * 3_600_000, 12_800, '3 open breaks (1 value, 1 unbilled, 1 unmatched billing row).');

    const raEvents: Array<[string, string, string, string]> = [
        ['BATCH_COMMITTED', 'INFO', 'switch_cdr_ingest', 'Committed 812 files (2.1M CDRs).'],
        ['BATCH_COMMITTED', 'INFO', 'billing_rated_load', 'Loaded 2.09M rated events.'],
        ['ALERT_FIRED', 'WARN', 'billing_rated_load', 'Rated/collected delta above 0.5% for 2026-06-25.'],
        ['FILE_QUARANTINED', 'WARN', 'switch_cdr_ingest', 'Quarantined 1 malformed CDR file.'],
        ['JOB_SUCCEEDED', 'INFO', 'recon_daily', 'Reconciliation produced 3 open breaks.'],
        ['BATCH_FAILED', 'ERROR', 'billing_rated_load', 'Replica lag exceeded extract window; batch retried.'],
    ];
    raEvents.forEach(([type, level, pipeline, message], i) => {
        const ts = now - i * 1_800_000;
        store.put<EventRow>(space, EVENTS_COLL, `evt-ra-${i}`, {
            eventId: `evt-ra-${i}`, ts, timestamp: new Date(ts).toISOString(), level, type,
            source: 'engine', pipeline, correlationId: i < 3 ? 'corr-ra-1' : null, message, attributes: {},
        });
    });

    store.put<AlertRule>(space, ALERT_RULES_COLL, 'billing_delta_pct', {
        name: 'billing_delta_pct', metric: 'billing_delta_pct', comparator: 'gt', threshold: 0.5, window: '1d', severity: 'CRITICAL',
    });
    store.put<AlertRule>(space, ALERT_RULES_COLL, 'quarantine_spike', {
        name: 'quarantine_spike', metric: 'quarantined_files', comparator: 'gt', threshold: 5, window: '1h', severity: 'WARNING',
    });
    store.put<FiredAlert>(space, FIRED_ALERTS_COLL, 'alert-ra-1', {
        rule: 'billing_delta_pct', severity: 'CRITICAL', pipeline: 'billing_rated_load', metric: 'billing_delta_pct',
        value: 0.62, comparator: 'gt', threshold: 0.5, window: '1d', epochMillis: now - 5_400_000,
        message: 'Rated/collected revenue delta 0.62% (threshold 0.5%)',
    });

    const incidents: Array<[string, string, string, string, string]> = [
        ['INCIDENT', 'Revenue delta above threshold on 2026-06-25', 'OPEN', 'CRITICAL', 'HIGH'],
        ['CASE', 'Premium-tariff under-billing investigation', 'IN_PROGRESS', 'WARNING', 'HIGH'],
        ['ALERT', 'Quarantined CDR file on switch_cdr_ingest', 'RESOLVED', 'WARNING', 'MEDIUM'],
    ];
    incidents.forEach(([objectType, title, status, severity, priority], i) => {
        const ts = now - (i + 1) * 3_600_000;
        store.put<OperationalObject>(space, OPS_OBJECTS_COLL, `${objectType.toLowerCase()}-ra-${i}`, {
            id: `${objectType.toLowerCase()}-ra-${i}`, objectType, title,
            description: 'Seeded by the Telecom RA space template.', status, severity, priority,
            owner: 'ra-ops', assignee: i % 2 ? 'alice' : 'bob', correlationId: 'corr-ra-1',
            attributes: { pipeline: 'billing_rated_load' }, createdAt: ts, updatedAt: ts + 600_000,
            closedAt: status === 'CLOSED' ? ts + 1_200_000 : 0,
        });
    });
}
