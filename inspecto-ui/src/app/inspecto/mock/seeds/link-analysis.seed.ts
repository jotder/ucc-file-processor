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
 * **Link Analysis** Space-Template seed pack (W5) — entity/link tables built from xDRs
 * (`entities`/`links` in SAMPLE_SOURCES; community 3 is the seeded ring candidate: two high-risk
 * subscribers sharing a device). The Entity/Link **graph** Visualization Type is C5 — until it
 * lands, the datasets power tabular/chart widgets and the ring shows up as an investigation Case.
 */
export function seedLinkAnalysis(store: MockStore, space: string): void {
    const now = Date.now();
    const iso = (offsetMin: number): string => new Date(now + offsetMin * 60_000).toISOString();
    seedIconMap(store, space);

    // ── Workbench ───────────────────────────────────────────────────────────────────────────────
    const connections: ConnectionProfile[] = [
        { id: 'xdr_warehouse', connector: 'db', host: 'dwh.telco.example', port: 5432, database: 'xdr', username: 'graph_ro', password: '${ENV:DWH_PW}', description: 'xDR warehouse (entity/link build source)' },
    ];
    for (const c of connections) store.put(space, CONNECTIONS_COLL, c.id, c);

    putComponent(store, space, 'schema', 'entity_record', {
        fields: [{ name: 'id', type: 'string' }, { name: 'entity_type', type: 'string' }, { name: 'risk_score', type: 'decimal' }, { name: 'community', type: 'integer' }],
    });
    putComponent(store, space, 'transform', 'min_link_weight', { type: 'transform.filter', where: 'weight >= 1' });
    putComponent(store, space, 'sink', 'graph_store', { type: 'sink.persistent', format: 'parquet', partitions: ['link_type'] });

    store.put(space, PIPELINES_COLL, 'entity_link_build', {
        name: 'entity_link_build',
        active: true,
        nodes: [
            { id: 'extract', type: 'collector.database', name: 'Extract xDR pairs', use: 'connections/xdr_warehouse' },
            { id: 'aggregate', type: 'transform.aggregate', name: 'Roll up to entities + links' },
            { id: 'prune', type: 'transform.filter', name: 'Prune weak links', config: { predicate: 'weight >= 1' } },
            { id: 'store', type: 'sink.file', name: 'Graph store', config: { format: 'PARQUET', partition_by: 'link_type' } },
        ],
        edges: [
            { from: 'extract', rel: 'success', to: 'aggregate' },
            { from: 'aggregate', rel: 'success', to: 'prune' },
            { from: 'prune', rel: 'kept', to: 'store' },
        ],
    });

    // ── Studio ──────────────────────────────────────────────────────────────────────────────────
    putComponent(store, space, 'dataset', 'entities', {
        name: 'entities', kind: 'virtual', sourceName: 'entities',
        query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
        physicalRef: null,
        columns: [
            { name: 'id', type: 'string', role: 'dimension' },
            { name: 'label', type: 'string', role: 'dimension' },
            { name: 'entity_type', type: 'string', role: 'dimension' },
            { name: 'risk_score', type: 'number', role: 'measure' },
            { name: 'community', type: 'number', role: 'dimension' },
        ],
        measures: [{ id: 'avg_risk', label: 'Average risk', expression: 'avg(risk_score)' }],
        viz: null,
    });
    putComponent(store, space, 'dataset', 'links', {
        name: 'links', kind: 'virtual', sourceName: 'links',
        query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
        physicalRef: null,
        columns: [
            { name: 'id', type: 'string', role: 'dimension' },
            { name: 'source', type: 'string', role: 'dimension' },
            { name: 'target', type: 'string', role: 'dimension' },
            { name: 'link_type', type: 'string', role: 'dimension' },
            { name: 'weight', type: 'number', role: 'measure' },
            { name: 'first_seen', type: 'date', role: 'temporal' },
        ],
        measures: [], viz: null,
    });

    putComponent(store, space, 'widget', 'entity_count', {
        name: 'entity_count', datasetId: 'entities', vizType: 'kpi',
        controls: { value: [{ field: 'risk_score', agg: 'count' }] },
        description: 'Entities in the current graph build', tags: ['graph'],
    });
    putComponent(store, space, 'widget', 'links_by_type', {
        name: 'links_by_type', datasetId: 'links', vizType: 'pie',
        controls: { x: [{ field: 'link_type' }], y: [{ field: 'weight', agg: 'sum' }] },
        description: 'Relationship mix (calls / shared devices / payments)', tags: ['graph'],
    });
    putComponent(store, space, 'widget', 'risk_by_entity_type', {
        name: 'risk_by_entity_type', datasetId: 'entities', vizType: 'bar',
        controls: { x: [{ field: 'entity_type' }], y: [{ field: 'risk_score', agg: 'avg' }] },
        description: 'Average risk per entity type', tags: ['graph', 'risk'],
    });
    putComponent(store, space, 'dashboard', 'link_overview', {
        name: 'link_overview',
        tiles: [
            { widgetId: 'entity_count', span: 1 },
            { widgetId: 'links_by_type', span: 1 },
            { widgetId: 'risk_by_entity_type', span: 2 },
        ],
        filter: { kind: 'group', op: 'AND', items: [] },
        exposedFields: ['entity_type', 'link_type'],
    });

    // ── Business ────────────────────────────────────────────────────────────────────────────────
    putComponent(store, space, 'requirement', 'fraud_ring_detection_graph', {
        id: 'fraud_ring_detection_graph',
        title: 'Fraud-ring detection graph',
        kind: 'report',
        description: 'Interactive entity/link graph over subscriber-device-account relationships, with community detection to surface ring candidates.',
        status: 'accepted',
        submittedAt: iso(-60 * 24 * 10),
        decisionNote: 'Accepted — delivery blocked on the Entity/Link Graph Visualization Type (C5).',
        decidedAt: iso(-60 * 24 * 9),
    });

    // ── Ops ─────────────────────────────────────────────────────────────────────────────────────
    store.put<JobDetail>(space, JOBS_COLL, 'graph_build_nightly', {
        name: 'graph_build_nightly', type: 'ingest', cron: '0 0 3 * * *', onPipeline: null, enabled: true,
        lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 9), nextFire: iso(60 * 15), catchUp: false,
        params: { pipeline: 'entity_link_build' },
    });
    recordRun(store, space, 'graph_build_nightly', 'CRON', 'SUCCESS', now - 9 * 3_600_000, 187_000, 'Built 6 entities, 5 links; 1 new community flagged.');

    const laEvents: Array<[string, string, string]> = [
        ['JOB_SUCCEEDED', 'INFO', 'Nightly graph build completed (6 entities / 5 links).'],
        ['ALERT_FIRED', 'WARN', 'New high-risk community detected (community 3, 4 members).'],
        ['BATCH_COMMITTED', 'INFO', 'Committed link partitions: calls, shared_device, payment.'],
    ];
    laEvents.forEach(([type, level, message], i) => {
        const ts = now - i * 3_600_000;
        store.put(space, SIGNALS_COLL, `evt-la-${i}`, eventToSignal({
            eventId: `evt-la-${i}`, ts, timestamp: new Date(ts).toISOString(), level, type,
            source: 'engine', pipeline: 'entity_link_build', correlationId: i === 1 ? 'corr-la-1' : null, message, attributes: {},
        }));
    });

    store.put<OperationalObject>(space, OPS_OBJECTS_COLL, 'case-la-0', {
        id: 'case-la-0', objectType: 'CASE', title: 'Ring candidate: community 3 (shared device, LV traffic)',
        description: 'Two high-risk subscribers share IMEI 356938035643809 and route payments through account A-1003. Seeded by the Link Analysis space template.',
        status: 'OPEN', severity: 'CRITICAL', priority: 'HIGH', owner: 'graph-ops', assignee: 'dana',
        correlationId: 'corr-la-1', attributes: { pipeline: 'entity_link_build', community: '3' },
        createdAt: now - 8 * 3_600_000, updatedAt: now - 7 * 3_600_000, closedAt: 0,
    });
}
