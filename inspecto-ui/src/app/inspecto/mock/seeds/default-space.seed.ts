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
