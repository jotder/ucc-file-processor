import type { AlertRule } from '../../api/alerts.service';
import type { ConnectionProfile } from '../../api/connections.service';
import type { JobDetail } from '../../api/jobs.service';
import type { OperationalObject } from '../../api/objects.service';
import { CONNECTIONS_COLL } from '../handlers/connections.handler';
import { JOBS_COLL, recordRun } from '../handlers/jobs.handler';
import { ALERT_RULES_COLL, OPS_OBJECTS_COLL } from '../handlers/ops.handler';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { alertToSignal, eventToSignal } from '../../signal/signal';
import { SIGNALS_COLL } from '../signals';
import { MockStore } from '../mock-store';
import { putComponent, seedIconMap } from './seed-utils';

/**
 * **Fraud Management** Space-Template seed pack (W5) — scored usage events (`fraud_events` in
 * SAMPLE_SOURCES) flow through a scoring pipeline; high-risk traffic (IRSF / SIM-box patterns to
 * LV/SL destinations) raises alerts that become investigation Cases. The `high_risk_calls`
 * dataset is a real Query-Core filter (`risk_score > 0.8`), so drilling works end-to-end.
 */
export function seedFraudMgmt(store: MockStore, space: string): void {
    const now = Date.now();
    const iso = (offsetMin: number): string => new Date(now + offsetMin * 60_000).toISOString();
    seedIconMap(store, space);

    // ── Workbench ───────────────────────────────────────────────────────────────────────────────
    const connections: ConnectionProfile[] = [
        { id: 'mediation_sftp', connector: 'sftp', host: 'mediation.telco.example', port: 22, basePath: '/xdr/out', username: 'fms', password: '${ENV:MEDIATION_SFTP_PW}', description: 'Mediation xDR feed (5-minute drops)' },
        { id: 'hlr_db', connector: 'db', host: 'hlr-replica.telco.example', port: 5432, database: 'subscribers', username: 'fms_ro', password: '${ENV:HLR_DB_PW}', description: 'Subscriber reference (HLR replica)' },
    ];
    for (const c of connections) store.put(space, CONNECTIONS_COLL, c.id, c);

    putComponent(store, space, 'grammar', 'xdr_pipe', { delimiter: '|', has_header: false });
    putComponent(store, space, 'schema', 'scored_event', {
        fields: [{ name: 'id', type: 'integer' }, { name: 'msisdn', type: 'string' }, { name: 'dest_country', type: 'string' }, { name: 'risk_score', type: 'decimal' }],
    });
    putComponent(store, space, 'transform', 'keep_high_risk', { type: 'transform.filter', where: 'risk_score > 0.8' });
    putComponent(store, space, 'sink', 'fraud_case_queue', { type: 'sink.persistent', format: 'parquet', partitions: ['event_date'] });

    store.put(space, PIPELINES_COLL, 'fraud_scoring', {
        name: 'fraud_scoring',
        active: true,
        nodes: [
            { id: 'collect', type: 'collector.file', name: 'Collect xDRs', use: 'connections/mediation_sftp', config: { include: 'glob:**/*.dat' } },
            { id: 'parse', type: 'parser.dsv', name: 'Parse xDR', config: { delimiter: '|', header: false } },
            { id: 'score', type: 'transform.aggregate', name: 'Velocity + destination scoring' },
            { id: 'gate', type: 'transform.filter', name: 'Keep high-risk', config: { predicate: 'risk_score > 0.8' } },
            { id: 'alert', type: 'transform.alert', name: 'Raise fraud alert' },
            { id: 'queue', type: 'sink.database', name: 'Case queue' },
        ],
        edges: [
            { from: 'collect', rel: 'success', to: 'parse' },
            { from: 'parse', rel: 'success', to: 'score' },
            { from: 'score', rel: 'success', to: 'gate' },
            { from: 'gate', rel: 'kept', to: 'alert' },
            { from: 'alert', rel: 'success', to: 'queue' },
        ],
    });

    // ── Studio ──────────────────────────────────────────────────────────────────────────────────
    const eventColumns = [
        { name: 'id', type: 'number', role: 'dimension' },
        { name: 'msisdn', type: 'string', role: 'dimension' },
        { name: 'event_type', type: 'string', role: 'dimension' },
        { name: 'dest_country', type: 'string', role: 'dimension' },
        { name: 'duration_s', type: 'number', role: 'measure' },
        { name: 'risk_score', type: 'number', role: 'measure' },
        { name: 'event_time', type: 'date', role: 'temporal' },
    ];
    putComponent(store, space, 'dataset', 'fraud_events', {
        name: 'fraud_events', kind: 'virtual', sourceName: 'fraud_events',
        query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
        physicalRef: null, columns: eventColumns,
        measures: [{ id: 'avg_risk', label: 'Average risk', expression: 'avg(risk_score)' }],
        viz: null,
    });
    putComponent(store, space, 'dataset', 'high_risk_calls', {
        name: 'high_risk_calls', kind: 'virtual', sourceName: 'fraud_events',
        query: {
            projection: '*',
            where: { kind: 'group', op: 'AND', items: [{ kind: 'condition', field: 'risk_score', operator: '>', value: '0.8' }] },
            sqlOverride: null,
        },
        physicalRef: null, columns: eventColumns, measures: [], viz: null,
    });

    putComponent(store, space, 'widget', 'high_risk_events', {
        name: 'high_risk_events', datasetId: 'high_risk_calls', vizType: 'kpi',
        controls: { value: [{ field: 'risk_score', agg: 'count' }] },
        description: 'Events currently above the 0.8 risk threshold', tags: ['fraud'],
    });
    putComponent(store, space, 'widget', 'risk_by_destination', {
        name: 'risk_by_destination', datasetId: 'fraud_events', vizType: 'bar',
        controls: { x: [{ field: 'dest_country' }], y: [{ field: 'risk_score', agg: 'avg' }] },
        description: 'Average risk score per destination country (IRSF watchlist)', tags: ['fraud', 'irsf'],
    });
    putComponent(store, space, 'widget', 'events_by_type', {
        name: 'events_by_type', datasetId: 'fraud_events', vizType: 'pie',
        controls: { x: [{ field: 'event_type' }], y: [{ field: 'duration_s', agg: 'sum' }] },
        description: 'Traffic mix by event type', tags: ['fraud'],
    });
    putComponent(store, space, 'dashboard', 'fraud_overview', {
        name: 'fraud_overview',
        tiles: [
            { widgetId: 'high_risk_events', span: 1 },
            { widgetId: 'events_by_type', span: 1 },
            { widgetId: 'risk_by_destination', span: 2 },
        ],
        filter: { kind: 'group', op: 'AND', items: [] },
        exposedFields: ['dest_country', 'event_type'],
    });

    // ── Business ────────────────────────────────────────────────────────────────────────────────
    putComponent(store, space, 'requirement', 'irsf_destination_monitoring', {
        id: 'irsf_destination_monitoring',
        title: 'IRSF destination monitoring',
        kind: 'rule',
        description: 'Flag sustained long-duration calls to known IRSF ranges (LV, SL) within 15 minutes of onset.',
        status: 'accepted',
        submittedAt: iso(-60 * 24 * 5),
        decisionNote: 'Accepted — velocity rule lands with the scoring pipeline.',
        decidedAt: iso(-60 * 24 * 4),
    });

    // ── Ops ─────────────────────────────────────────────────────────────────────────────────────
    store.put<JobDetail>(space, JOBS_COLL, 'fraud_scoring_5min', {
        name: 'fraud_scoring_5min', type: 'ingest', cron: '0 */5 * * * *', onPipeline: null, enabled: true,
        lastStatus: 'SUCCESS', lastRunTime: iso(-5), nextFire: iso(5), catchUp: false,
        params: { pipeline: 'fraud_scoring' },
    });
    recordRun(store, space, 'fraud_scoring_5min', 'CRON', 'SUCCESS', now - 5 * 60_000, 4_100, 'Scored 18,204 events; 3 high-risk.');
    recordRun(store, space, 'fraud_scoring_5min', 'CRON', 'SUCCESS', now - 10 * 60_000, 3_900, 'Scored 17,940 events; 4 high-risk.');

    const fmsEvents: Array<[string, string, string]> = [
        ['ALERT_FIRED', 'ERROR', 'SIM-box velocity pattern on 8801700000011 (3 calls > 29 min to LV).'],
        ['ALERT_FIRED', 'WARN', 'IRSF spike: SL traffic up 240% vs 7-day baseline.'],
        ['BATCH_COMMITTED', 'INFO', 'Committed 18,204 scored events.'],
        ['JOB_SUCCEEDED', 'INFO', 'Scoring window completed in 4.1s.'],
    ];
    fmsEvents.forEach(([type, level, message], i) => {
        const ts = now - i * 900_000;
        store.put(space, SIGNALS_COLL, `evt-fms-${i}`, eventToSignal({
            eventId: `evt-fms-${i}`, ts, timestamp: new Date(ts).toISOString(), level, type,
            source: 'engine', pipeline: 'fraud_scoring', correlationId: i < 2 ? 'corr-fms-1' : null, message, attributes: {},
        }));
    });

    store.put<AlertRule>(space, ALERT_RULES_COLL, 'simbox_velocity', {
        name: 'simbox_velocity', metric: 'long_calls_per_msisdn_15m', comparator: 'gt', threshold: 2, window: '15m', severity: 'CRITICAL',
    });
    store.put<AlertRule>(space, ALERT_RULES_COLL, 'irsf_spike', {
        name: 'irsf_spike', metric: 'irsf_dest_minutes_pct', comparator: 'gt', threshold: 100, window: '1h', severity: 'WARNING',
    });
    store.put(space, SIGNALS_COLL, 'alert-fms-1', alertToSignal({
        rule: 'simbox_velocity', severity: 'CRITICAL', pipeline: 'fraud_scoring', metric: 'long_calls_per_msisdn_15m',
        value: 3, comparator: 'gt', threshold: 2, window: '15m', epochMillis: now - 1_200_000,
        message: '8801700000011: 3 calls over 29 min to LV within 15 minutes',
    }, 'alert-fms-1'));

    const cases: Array<[string, string, string, string, string]> = [
        ['CASE', 'SIM-box investigation: 8801700000011', 'IN_PROGRESS', 'CRITICAL', 'HIGH'],
        ['INCIDENT', 'IRSF spike to SL ranges', 'OPEN', 'WARNING', 'HIGH'],
        ['CASE', 'Chargeback cluster on account A-1002', 'OPEN', 'WARNING', 'MEDIUM'],
    ];
    cases.forEach(([objectType, title, status, severity, priority], i) => {
        const ts = now - (i + 1) * 5_400_000;
        store.put<OperationalObject>(space, OPS_OBJECTS_COLL, `${objectType.toLowerCase()}-fms-${i}`, {
            id: `${objectType.toLowerCase()}-fms-${i}`, objectType, title,
            description: 'Seeded by the Fraud Management space template.', status, severity, priority,
            owner: 'fms-ops', assignee: i % 2 ? 'dana' : 'raj', correlationId: 'corr-fms-1',
            attributes: { pipeline: 'fraud_scoring' }, createdAt: ts, updatedAt: ts + 600_000,
            closedAt: status === 'CLOSED' ? ts + 1_200_000 : 0,
        });
    });
}
