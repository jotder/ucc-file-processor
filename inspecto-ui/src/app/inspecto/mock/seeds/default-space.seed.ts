import type { AuthoredPipeline } from '../../api/pipelines.service';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { MockStore } from '../mock-store';
import { seedOperations } from './operations.seed';
import { putComponent, seedIconMap } from './seed-utils';

/**
 * The default seed pack — the consolidated seeds formerly baked into the studio / pipeline / jobs /
 * ops / connection / demo mock interceptors, applied once per space by `MockStore.ensureSeeded`.
 * Space-Template seed packs (Telecom RA / FMS / Financial Audit / Link Analysis, plan W5) will sit
 * alongside this one.
 */
export function seedDefaultSpace(store: MockStore, space: string): void {
    // Operations surface (jobs/runs · events · alerts · objects · enrichment · connections · notifications).
    seedOperations(store, space);

    // ── Studio: a demo dataset so /studio/datasets isn't empty ─────────────────────────────────
    putComponent(store, space, 'dataset', 'cdr_sample', {
        name: 'cdr_sample',
        kind: 'virtual',
        sourceName: 'cdr',
        query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
        physicalRef: null,
        columns: [
            { name: 'id', type: 'number', role: 'dimension' },
            { name: 'msisdn', type: 'string', role: 'dimension' },
            { name: 'duration_s', type: 'number', role: 'measure' },
            { name: 'bytes_used', type: 'number', role: 'measure' },
            { name: 'cost_usd', type: 'number', role: 'measure' },
            { name: 'tariff', type: 'string', role: 'dimension' },
            { name: 'event_time', type: 'date', role: 'temporal' },
        ],
        measures: [{ id: 'total_duration', label: 'Total duration', expression: 'sum(duration_s)' }],
        viz: null,
    });

    // ── Link Analysis (C5): four example graphs at rising complexity for user testing — each a
    //    link-table dataset (rows in SAMPLE_SOURCES) + a saved view, so /studio/link-analysis
    //    loads them one-click under Saved views ────────────────────────────────────────────────
    const exampleGraphs: Array<{ id: string; name: string; description: string }> = [
        {
            id: 'graph_simple', name: 'Example 1 — Simple star',
            description: 'One hub calling five subscribers — a first look at the canvas.',
        },
        {
            id: 'graph_moderate', name: 'Example 2 — Two clusters',
            description: 'A call ring and a chain joined by one bridge — try Shortest path and the type filter.',
        },
        {
            id: 'graph_mindmap', name: 'Example 3 — Mind map',
            description: 'A Data Quality topic tree — try Explain node on a branch.',
        },
        {
            id: 'graph_complex', name: 'Example 4 — Fraud network',
            description: 'Three rings, mule accounts and a cash-out hub — try Centrality and Communities.',
        },
    ];
    for (const g of exampleGraphs) {
        putComponent(store, space, 'dataset', g.id, {
            name: g.id,
            kind: 'virtual',
            sourceName: g.id,
            query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
            physicalRef: null,
            columns: [
                { name: 'id', type: 'string', role: 'dimension' },
                { name: 'source', type: 'string', role: 'dimension' },
                { name: 'target', type: 'string', role: 'dimension' },
                { name: 'link_type', type: 'string', role: 'dimension' },
            ],
            measures: [],
            viz: null,
        });
        putComponent(store, space, 'link-analysis-view', g.id.replace(/_/g, '-'), {
            name: g.name,
            description: g.description,
            sourceId: 'entity-projection',
            query: { projection: { datasetId: g.id, sourceCol: 'source', targetCol: 'target', linkKindCol: 'link_type' } },
        });
    }

    // ── Geo Map Analysis: a coordinate-bearing dataset + a saved Geo View, so /studio/geo-map
    //    demos one-click under Saved views ────────────────────────────────────────────────────
    putComponent(store, space, 'dataset', 'cell_sites', {
        name: 'cell_sites',
        kind: 'virtual',
        sourceName: 'cell_sites',
        query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
        physicalRef: null,
        columns: [
            { name: 'id', type: 'string', role: 'dimension' },
            { name: 'site', type: 'string', role: 'dimension' },
            { name: 'site_type', type: 'string', role: 'dimension' },
            { name: 'lat', type: 'number', role: 'dimension' },
            { name: 'lon', type: 'number', role: 'dimension' },
            { name: 'seen_time', type: 'date', role: 'temporal' },
        ],
        measures: [],
        viz: null,
    });
    putComponent(store, space, 'geo-map-view', 'dhaka-network', {
        name: 'Example — Dhaka cell network',
        description: 'A tower grid, a hopping device and a roaming trail — try the type filter and click a point.',
        sourceId: 'dataset',
        query: {
            projection: {
                datasetId: 'cell_sites', latCol: 'lat', lonCol: 'lon',
                entityCol: 'site', kindCol: 'site_type', timeCol: 'seen_time',
            },
        },
    });

    // ── Reconciliation (C9): the two RA sides as datasets + a seeded reconciliation over them ──────
    for (const side of ['switch_cdr', 'billing_cdr'] as const) {
        putComponent(store, space, 'dataset', side, {
            name: side,
            kind: 'virtual',
            sourceName: side,
            query: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: null },
            physicalRef: null,
            columns: [
                { name: 'id', type: 'number', role: 'dimension' },
                { name: 'msisdn', type: 'string', role: 'dimension' },
                { name: 'duration_s', type: 'number', role: 'measure' },
                { name: 'cost_usd', type: 'number', role: 'measure' },
                { name: 'event_time', type: 'date', role: 'temporal' },
            ],
            measures: [],
            viz: null,
        });
    }
    putComponent(store, space, 'reconciliation', 'switch_vs_billing', {
        name: 'switch_vs_billing',
        leftDataset: 'switch_cdr',
        rightDataset: 'billing_cdr',
        keyColumns: ['id'],
        compareColumns: [{ column: 'cost_usd', toleranceType: 'absolute', tolerance: 0.02 }],
        breaks: [],
        lastRunAt: null,
    });

    // ── Registry kinds: options for the in-graph "choose a grammar/transform/sink" picker ──────
    putComponent(store, space, 'grammar', 'cdr_csv', { delimiter: ',', has_header: true });
    putComponent(store, space, 'grammar', 'pipe_delimited', { delimiter: '|', has_header: false });
    putComponent(store, space, 'schema', 'cdr_record', {
        fields: [{ name: 'msisdn', type: 'string' }, { name: 'duration_s', type: 'integer' }],
    });
    putComponent(store, space, 'transform', 'drop_test_rows', {
        type: 'transform.filter', where: "msisdn NOT LIKE '0000%'",
    });
    putComponent(store, space, 'sink', 'cdr_parquet', {
        type: 'sink.persistent', format: 'parquet', partitions: ['event_date'],
    });

    // ── Authored pipelines: two samples so open/edit works immediately ──────────────────────────
    const cdrIngest: AuthoredPipeline = {
        name: 'cdr_ingest',
        active: true,
        nodes: [
            { id: 'collect', type: 'collector.file', name: 'Collect CDR drops', use: 'connections/cdr_sftp_prod', config: { include: 'glob:**/*.csv.gz' } },
            { id: 'parse', type: 'parser.dsv', name: 'Parse CSV', config: { delimiter: ',', header: true } },
            { id: 'filter', type: 'transform.filter', name: 'Drop test rows', config: { predicate: "msisdn NOT LIKE '0000%'" } },
            { id: 'write', type: 'sink.file', name: 'CDR parquet', config: { format: 'PARQUET', partition_by: 'event_date' } },
        ],
        edges: [
            { from: 'collect', rel: 'success', to: 'parse' },
            { from: 'parse', rel: 'success', to: 'filter' },
            { from: 'filter', rel: 'kept', to: 'write' },
        ],
    };
    const subscriberLoad: AuthoredPipeline = {
        name: 'subscriber_load',
        active: false,
        nodes: [
            { id: 'extract', type: 'collector.database', name: 'Extract subscribers' },
            { id: 'daily', type: 'transform.aggregate', name: 'Daily counts' },
            { id: 'load', type: 'sink.database', name: 'Load summary' },
        ],
        edges: [
            { from: 'extract', rel: 'success', to: 'daily' },
            { from: 'daily', rel: 'success', to: 'load' },
        ],
    };
    store.put(space, PIPELINES_COLL, cdrIngest.name, cdrIngest);
    store.put(space, PIPELINES_COLL, subscriberLoad.name, subscriberLoad);

    // ── Processor icon map (category defaults + sub-type overrides) ─────────────────────────────
    seedIconMap(store, space);

    // ── ASN.1 schema-module library (parser config `schema_spec` picker) ────────────────────────
    store.put(space, 'asn1-module', 'cdr_3gpp_ts32297', {
        name: 'cdr_3gpp_ts32297',
        text: [
            '-- 3GPP TS 32.297 CDR record (abridged sample)',
            'CallEventRecord ::= SEQUENCE {',
            '    recordType        [0] INTEGER,',
            '    servedIMSI        [1] OCTET STRING,',
            '    callDuration      [2] INTEGER,',
            '    recordOpeningTime [3] GeneralizedTime',
            '}',
        ].join('\n'),
    });
    store.put(space, 'asn1-module', 'map_rel99', {
        name: 'map_rel99',
        text: [
            '-- MAP Rel-99 (abridged sample)',
            'MAP-Protocol DEFINITIONS ::= BEGIN',
            '    SubscriberInfo ::= SEQUENCE {',
            '        imsi      [0] OCTET STRING,',
            '        msisdn    [1] OCTET STRING OPTIONAL',
            '    }',
            'END',
        ].join('\n'),
    });
}
