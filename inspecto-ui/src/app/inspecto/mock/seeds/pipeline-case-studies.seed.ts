import type { AuthoredPipeline } from '../../api/pipelines.service';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { MockStore } from '../mock-store';
import { putComponent } from './seed-utils';

/**
 * Pipeline case-study pack (CS1–CS5) — five boundary-pushing authored pipelines for user testing,
 * mirroring the Geo Map pack (`docs/superpower/pipeline-case-studies.md`). Each pins a different
 * editor boundary: canvas scale + named-route fan-out/fan-in (CS1), clone-mode streaming with
 * alerting (CS2), disconnected multi-leg feeds (CS3), the full exotic-parser gauntlet via reusable
 * grammars (CS4), and every control-flow relation wired on a deep chain (CS5).
 * Invariants pinned by `modules/admin/pipelines/pipeline-case-studies.spec.ts`.
 */

/** Reusable grammars the case-study parser nodes bind via `use: 'grammar/<id>'` — together they
 *  cover every exotic parser format (asn1/json/xlsx/xml/html/txt-fixedwidth/parquet). */
export const CASE_STUDY_GRAMMARS: Record<string, Record<string, unknown>> = {
    cdr_asn1_ber: { parser_type: 'asn1', encoding_rules: 'BER', schema_spec: 'cdr_3gpp_ts32297', extension: 'asn1', decode_implicit: true },
    sim_events_ndjson: { parser_type: 'json', mode: 'ndjson', root_path: '$', extension: 'jsonl', encoding: 'UTF-8' },
    regulatory_xlsx: { parser_type: 'xlsx', sheet: 'Disclosures', header_position: 'top', skip_rows: 2, extension: 'xlsx' },
    invoice_xml: { parser_type: 'xml', record_xpath: '//Invoice/Line', namespace_aware: true, extension: 'xml' },
    tariff_html: { parser_type: 'html', table_selector: 'table.tariffs', header_position: 'top', extension: 'html' },
    mainframe_fixed: { parser_type: 'txt', frontend: 'fixedwidth', record_length: 128, extension: 'dat', encoding: 'ISO-8859-1' },
    archive_parquet: { parser_type: 'parquet', columns: 'msisdn,event_time,cost_usd', extension: 'parquet' },
};

/** CS1 — RA mediation backbone: 3 collectors → 2 parsers → 3 normalizers → dedup → 4 named routes
 *  → per-service rating → one aggregate fan-in → 3 sinks + revenue alert. ~21 nodes: the canvas,
 *  layout and edge-label stress test. */
const MEDIATION_BACKBONE: AuthoredPipeline = {
    name: 'mediation_backbone',
    active: true,
    nodes: [
        { id: 'c_sftp', type: 'collector.file', name: 'Switch ASN.1 drops', use: 'connections/cdr_sftp_prod', config: { include: 'glob:**/*.asn1', min_age_seconds: 60 } },
        { id: 'c_stream', type: 'collector.stream', name: 'CDR event bus', config: { topic: 'cdr.events', group_id: 'mediation', batch_size: 2000 } },
        { id: 'c_db', type: 'collector.database', name: 'Roaming TAP-in', use: 'connections/pg_warehouse', config: { query: 'SELECT * FROM roaming_cdr WHERE loaded_at > :watermark', watermark_column: 'loaded_at', fetch_size: 10000 } },
        { id: 'p_asn1', type: 'parser.asn1', name: 'Decode 3GPP CDR', use: 'grammar/cdr_asn1_ber' },
        { id: 'p_json', type: 'parser.other', name: 'Parse event NDJSON', use: 'grammar/sim_events_ndjson' },
        { id: 'n_switch', type: 'transform.record', name: 'Normalize switch leg', config: { rename: 'calling_number=msisdn', cast: 'duration_s:int, cost_usd:decimal(18,6)' } },
        { id: 'n_stream', type: 'transform.record', name: 'Normalize stream leg', config: { rename: 'imsi=msisdn', drop: 'raw_payload' } },
        { id: 'n_roam', type: 'transform.record', name: 'Normalize roaming leg', config: { derive: 'cost_usd = sdr_amount * fx_rate' } },
        { id: 'dedup', type: 'transform.filter', name: 'Drop duplicates', config: { predicate: 'NOT is_duplicate(record_id)' } },
        { id: 'route', type: 'transform.route', name: 'Split by service', config: { mode: 'case', route_column: 'service_type' } },
        { id: 'rate_voice', type: 'transform.record', name: 'Rate voice', config: { derive: 'rated_usd = duration_s * tariff_rate / 60' } },
        { id: 'rate_sms', type: 'transform.record', name: 'Rate SMS', config: { derive: 'rated_usd = tariff_rate' } },
        { id: 'rate_data', type: 'transform.record', name: 'Rate data', config: { derive: 'rated_usd = bytes_used / 1048576 * tariff_rate' } },
        { id: 'rate_roam', type: 'transform.record', name: 'Rate roaming', config: { derive: 'rated_usd = cost_usd * roaming_uplift' } },
        { id: 'agg', type: 'transform.aggregate', name: 'Revenue rollup', config: { group_by: 'tariff, event_date', aggregations: 'SUM(rated_usd) AS revenue, COUNT(*) AS events' } },
        { id: 'a_rev', type: 'transform.alert', name: 'Revenue drop alert', config: { condition: 'revenue_delta_pct < -5', severity: 'CRITICAL' } },
        { id: 's_lake', type: 'sink.file', name: 'Rated CDR lake', use: 'sink/cdr_parquet', config: { format: 'PARQUET', partition_by: 'event_date', compression: 'zstd' } },
        { id: 's_wh', type: 'sink.database', name: 'Warehouse rollup', config: { table: 'rated_cdr_daily', mode: 'upsert', key_columns: 'tariff, event_date' } },
        { id: 's_quar', type: 'sink.file', name: 'Quarantine', config: { format: 'CSV', compression: 'gzip' } },
    ],
    edges: [
        { from: 'c_sftp', rel: 'success', to: 'p_asn1' },
        { from: 'c_stream', rel: 'success', to: 'p_json' },
        { from: 'c_db', rel: 'success', to: 'n_roam' },
        { from: 'p_asn1', rel: 'success', to: 'n_switch' },
        { from: 'p_asn1', rel: 'unmatched', to: 's_quar' },
        { from: 'p_json', rel: 'success', to: 'n_stream' },
        { from: 'p_json', rel: 'unmatched', to: 's_quar' },
        { from: 'n_switch', rel: 'success', to: 'dedup' },
        { from: 'n_stream', rel: 'success', to: 'dedup' },
        { from: 'n_roam', rel: 'success', to: 'dedup' },
        { from: 'dedup', rel: 'kept', to: 'route' },
        { from: 'dedup', rel: 'dropped', to: 's_quar' },
        { from: 'route', rel: 'route:voice', to: 'rate_voice' },
        { from: 'route', rel: 'route:sms', to: 'rate_sms' },
        { from: 'route', rel: 'route:data', to: 'rate_data' },
        { from: 'route', rel: 'route:roaming', to: 'rate_roam' },
        { from: 'rate_voice', rel: 'success', to: 'agg' },
        { from: 'rate_sms', rel: 'success', to: 'agg' },
        { from: 'rate_data', rel: 'success', to: 'agg' },
        { from: 'rate_roam', rel: 'success', to: 'agg' },
        { from: 'agg', rel: 'success', to: 's_lake' },
        { from: 'agg', rel: 'success', to: 's_wh' },
        { from: 'agg', rel: 'success', to: 'a_rev' },
    ],
};

/** CS2 — Streaming fraud velocity: clone-mode route (same rows to real-time triage AND archive),
 *  CRITICAL alerting, upsert sink with key columns. */
const FRAUD_VELOCITY: AuthoredPipeline = {
    name: 'fraud_velocity_stream',
    active: true,
    nodes: [
        { id: 'c_kafka', type: 'collector.stream', name: 'SIM-swap events', config: { topic: 'sim.swaps', group_id: 'fraud', batch_size: 500 } },
        { id: 'p_ndjson', type: 'parser.other', name: 'Parse NDJSON', use: 'grammar/sim_events_ndjson' },
        { id: 'derive', type: 'transform.record', name: 'Derive velocity', config: { derive: 'velocity_kmh = haversine(prev_loc, loc) / hours_between(prev_time, event_time)' } },
        { id: 'clone', type: 'transform.route', name: 'Tee real-time / archive', config: { mode: 'clone' } },
        { id: 'f_risk', type: 'transform.filter', name: 'High-risk only', config: { predicate: 'velocity_kmh > 900 OR swap_count_24h >= 3' } },
        { id: 'a_fraud', type: 'transform.alert', name: 'Impossible-travel alert', config: { condition: 'true', severity: 'CRITICAL' } },
        { id: 's_cases', type: 'sink.database', name: 'Fraud candidates', config: { table: 'fraud_candidates', mode: 'upsert', key_columns: 'msisdn' } },
        { id: 'agg5', type: 'transform.aggregate', name: '5-minute windows', config: { group_by: "msisdn, window('5m')", aggregations: 'COUNT(*) AS events, MAX(velocity_kmh) AS top_speed' } },
        { id: 's_archive', type: 'sink.file', name: 'Event archive', config: { format: 'PARQUET', partition_by: 'day', compression: 'snappy' } },
    ],
    edges: [
        { from: 'c_kafka', rel: 'success', to: 'p_ndjson' },
        { from: 'p_ndjson', rel: 'success', to: 'derive' },
        { from: 'derive', rel: 'success', to: 'clone' },
        { from: 'clone', rel: 'route:realtime', to: 'f_risk' },
        { from: 'clone', rel: 'route:archive', to: 'agg5' },
        { from: 'f_risk', rel: 'kept', to: 'a_fraud' },
        { from: 'a_fraud', rel: 'success', to: 's_cases' },
        { from: 'agg5', rel: 'success', to: 's_archive' },
    ],
};

/** CS3 — Financial-audit reconciliation feeds: two independent legs (S3 ASN.1 switch dump vs.
 *  watermarked billing extract) that never touch, plus a deliberately unwired 2-node draft island —
 *  three weakly-connected components on one canvas. Feeds the seeded `switch_vs_billing` recon. */
const RECON_FEEDS: AuthoredPipeline = {
    name: 'audit_recon_feeds',
    active: false,
    nodes: [
        { id: 'a_collect', type: 'collector.file', name: 'Switch dumps (S3)', use: 'connections/s3_archive', config: { include: 'glob:switch/**/*.asn1' } },
        { id: 'a_parse', type: 'parser.asn1', name: 'Decode switch CDR', use: 'grammar/cdr_asn1_ber' },
        { id: 'a_norm', type: 'transform.record', name: 'Normalize switch side', config: { rename: 'a_number=msisdn', cast: 'cost_usd:decimal(18,6)' } },
        { id: 'a_sink', type: 'sink.database', name: 'Load switch_cdr', config: { table: 'switch_cdr', mode: 'replace' } },
        { id: 'b_collect', type: 'collector.database', name: 'Billing extract', use: 'connections/pg_warehouse', config: { query: 'SELECT * FROM billing_events WHERE event_id > :watermark', watermark_column: 'event_id', fetch_size: 5000 } },
        { id: 'b_norm', type: 'transform.record', name: 'Normalize billing side', config: { cast: 'cost_usd:decimal(18,6)' } },
        { id: 'b_sink', type: 'sink.database', name: 'Load billing_cdr', config: { table: 'billing_cdr', mode: 'replace' } },
        { id: 'a_gap', type: 'transform.alert', name: 'Watermark gap alert', config: { condition: 'watermark_gap_minutes > 60', severity: 'WARNING' } },
        { id: 'd_collect', type: 'collector.file', name: 'DRAFT — legacy leg', description: 'Unwired draft: legacy mainframe side, pending sign-off.', use: 'connections/legacy_ftp_down', config: { include: 'glob:*.dat' } },
        { id: 'd_parse', type: 'parser.text', name: 'DRAFT — fixed-width decode', use: 'grammar/mainframe_fixed' },
    ],
    edges: [
        { from: 'a_collect', rel: 'success', to: 'a_parse' },
        { from: 'a_parse', rel: 'success', to: 'a_norm' },
        { from: 'a_norm', rel: 'success', to: 'a_sink' },
        { from: 'b_collect', rel: 'success', to: 'b_norm' },
        { from: 'b_collect', rel: 'gap', to: 'a_gap' },
        { from: 'b_norm', rel: 'success', to: 'b_sink' },
        { from: 'd_collect', rel: 'success', to: 'd_parse' },
    ],
};

/** CS4 — Format gauntlet: one dropzone routed by extension into five exotic parsers (XLSX / XML /
 *  HTML / Parquet / fixed-width TXT, all bound to reusable grammars), every reject wired, 5-way
 *  fan-in to one union. Covers every parser format the config dialog offers. */
const FORMAT_GAUNTLET: AuthoredPipeline = {
    name: 'format_gauntlet',
    active: false,
    nodes: [
        // No `use:` — a local dropzone needs no connection (and `local_inbox` must stay unreferenced:
        // it is the deletable fixture in connections.handler.spec.ts).
        { id: 'g_collect', type: 'collector.file', name: 'Mixed dropzone', config: { include: 'glob:drops/**/*', recursive: true } },
        { id: 'g_route', type: 'transform.route', name: 'Route by extension', config: { mode: 'case', route_column: 'file_ext' } },
        { id: 'p_xlsx', type: 'parser.other', name: 'Regulatory XLSX', use: 'grammar/regulatory_xlsx' },
        { id: 'p_xml', type: 'parser.other', name: 'Invoice XML', use: 'grammar/invoice_xml' },
        { id: 'p_html', type: 'parser.other', name: 'Tariff HTML table', use: 'grammar/tariff_html' },
        { id: 'p_parq', type: 'parser.other', name: 'Parquet re-ingest', use: 'grammar/archive_parquet' },
        { id: 'p_fixed', type: 'parser.text', name: 'Mainframe fixed-width', use: 'grammar/mainframe_fixed' },
        { id: 'u_norm', type: 'transform.record', name: 'Union normalize', config: { cast: 'amount:decimal(18,2)', derive: 'source_format = file_ext' } },
        { id: 'u_filter', type: 'transform.filter', name: 'Drop empty amounts', config: { predicate: 'amount IS NOT NULL' } },
        { id: 's_csv', type: 'sink.file', name: 'Unified CSV', config: { format: 'CSV', compression: 'gzip' } },
        { id: 's_rejects', type: 'sink.file', name: 'Reject pile', config: { format: 'CSV' } },
    ],
    edges: [
        { from: 'g_collect', rel: 'success', to: 'g_route' },
        { from: 'g_route', rel: 'route:xlsx', to: 'p_xlsx' },
        { from: 'g_route', rel: 'route:xml', to: 'p_xml' },
        { from: 'g_route', rel: 'route:html', to: 'p_html' },
        { from: 'g_route', rel: 'route:parquet', to: 'p_parq' },
        { from: 'g_route', rel: 'route:dat', to: 'p_fixed' },
        { from: 'p_xlsx', rel: 'success', to: 'u_norm' },
        { from: 'p_xml', rel: 'success', to: 'u_norm' },
        { from: 'p_html', rel: 'success', to: 'u_norm' },
        { from: 'p_parq', rel: 'success', to: 'u_norm' },
        { from: 'p_fixed', rel: 'success', to: 'u_norm' },
        { from: 'p_xlsx', rel: 'unmatched', to: 's_rejects' },
        { from: 'p_xml', rel: 'unmatched', to: 's_rejects' },
        { from: 'p_html', rel: 'unmatched', to: 's_rejects' },
        { from: 'p_parq', rel: 'unmatched', to: 's_rejects' },
        { from: 'p_fixed', rel: 'unmatched', to: 's_rejects' },
        { from: 'u_norm', rel: 'success', to: 'u_filter' },
        { from: 'u_filter', rel: 'kept', to: 's_csv' },
    ],
};

/** CS5 — Dead-letter torture: a 9-stage main chain off a known-down FTP connection where EVERY
 *  control relation is wired somewhere — failure, gap, unmatched, dropped, invalid — into
 *  quarantine files, a dead-letter table and two alert severities. */
const DEADLETTER_TORTURE: AuthoredPipeline = {
    name: 'deadletter_torture',
    active: true,
    nodes: [
        { id: 't_collect', type: 'collector.file', name: 'Legacy FTP gold feed', use: 'connections/legacy_ftp_down', config: { include: 'glob:*.csv', min_age_seconds: 300 } },
        { id: 't_parse', type: 'parser.dsv', name: 'Parse pipe-delimited', use: 'grammar/pipe_delimited' },
        { id: 't_rename', type: 'transform.record', name: 'Rename legacy fields', config: { rename: 'MSISDN_NR=msisdn, CALL_SECS=duration_s' } },
        { id: 't_screen', type: 'transform.filter', name: 'Screen test traffic', config: { predicate: "msisdn NOT LIKE '0000%'" } },
        { id: 't_cast', type: 'transform.record', name: 'Cast types', config: { cast: 'duration_s:int, cost_usd:decimal(18,6)' } },
        { id: 't_daily', type: 'transform.aggregate', name: 'Daily totals', config: { group_by: 'msisdn, event_date', aggregations: 'SUM(duration_s) AS total_secs, SUM(cost_usd) AS total_usd' } },
        { id: 't_derive', type: 'transform.record', name: 'Derive usage band', config: { derive: "usage_band = CASE WHEN total_secs > 3600 THEN 'heavy' ELSE 'normal' END" } },
        { id: 't_final', type: 'transform.filter', name: 'Positive totals only', config: { predicate: 'total_usd >= 0' } },
        { id: 's_gold', type: 'sink.database', name: 'Gold table', config: { table: 'gold_cdr_daily', mode: 'append' } },
        { id: 'a_conn', type: 'transform.alert', name: 'Feed health alert', config: { condition: 'true', severity: 'WARNING' } },
        { id: 'a_dead', type: 'transform.alert', name: 'Dead-letter alert', config: { condition: 'true', severity: 'CRITICAL' } },
        { id: 's_quar', type: 'sink.file', name: 'Unparsed quarantine', config: { format: 'CSV', compression: 'gzip' } },
        { id: 's_samples', type: 'sink.file', name: 'Dropped-row samples', config: { format: 'CSV' } },
        { id: 's_dlq', type: 'sink.database', name: 'Dead-letter table', config: { table: 'dead_letter', mode: 'append' } },
    ],
    edges: [
        { from: 't_collect', rel: 'success', to: 't_parse' },
        { from: 't_collect', rel: 'failure', to: 'a_conn' },
        { from: 't_collect', rel: 'gap', to: 'a_conn' },
        { from: 't_parse', rel: 'success', to: 't_rename' },
        { from: 't_parse', rel: 'unmatched', to: 's_quar' },
        { from: 't_parse', rel: 'failure', to: 'a_dead' },
        { from: 't_rename', rel: 'success', to: 't_screen' },
        { from: 't_rename', rel: 'invalid', to: 's_dlq' },
        { from: 't_screen', rel: 'kept', to: 't_cast' },
        { from: 't_screen', rel: 'dropped', to: 's_samples' },
        { from: 't_cast', rel: 'success', to: 't_daily' },
        { from: 't_cast', rel: 'invalid', to: 's_dlq' },
        { from: 't_daily', rel: 'success', to: 't_derive' },
        { from: 't_daily', rel: 'failure', to: 'a_dead' },
        { from: 't_derive', rel: 'success', to: 't_final' },
        { from: 't_final', rel: 'kept', to: 's_gold' },
        { from: 't_final', rel: 'dropped', to: 's_dlq' },
        { from: 's_gold', rel: 'failure', to: 'a_dead' },
    ],
};

export const PIPELINE_CASE_STUDIES: AuthoredPipeline[] = [
    MEDIATION_BACKBONE,
    FRAUD_VELOCITY,
    RECON_FEEDS,
    FORMAT_GAUNTLET,
    DEADLETTER_TORTURE,
];

export function seedPipelineCaseStudies(store: MockStore, space: string): void {
    for (const [id, content] of Object.entries(CASE_STUDY_GRAMMARS)) {
        putComponent(store, space, 'grammar', id, content);
    }
    for (const p of PIPELINE_CASE_STUDIES) {
        store.put(space, PIPELINES_COLL, p.name, p);
    }
}
