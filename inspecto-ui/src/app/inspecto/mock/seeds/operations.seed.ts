import type { AlertRule, FiredAlert } from '../../api/alerts.service';
import type { ConnectionProfile } from '../../api/connections.service';
import type { EventRow } from '../../api/events.service';
import type { JobDetail } from '../../api/jobs.service';
import type { EnrichmentJobView } from '../../api/models';
import type { OperationalObject } from '../../api/objects.service';
import { CONNECTIONS_COLL } from '../handlers/connections.handler';
import { NOTIFICATIONS_COLL, seedNotifications } from '../handlers/demo.handler';
import { JOBS_COLL, recordRun } from '../handlers/jobs.handler';
import {
    ALERT_RULES_COLL,
    ENRICHMENT_COLL,
    EVENTS_COLL,
    FIRED_ALERTS_COLL,
    OPS_OBJECTS_COLL,
} from '../handlers/ops.handler';
import { MockStore } from '../mock-store';

/**
 * The operations half of the default seed pack — jobs + runs/logs, events, alerts, operational
 * objects, enrichment jobs, connection profiles and notifications, consolidated from the old
 * `jobs-mock` / `ops-mock` / `connection-mock` / `demo-mock` interceptors. Called once per space by
 * `seedDefaultSpace`; from then on the liveness simulator keeps Runs/Events/Alerts moving.
 */
export function seedOperations(store: MockStore, space: string): void {
    const now = Date.now();
    const min = (offset: number): number => now + offset * 60_000;
    const iso = (offsetMin: number): string => new Date(min(offsetMin)).toISOString();

    // ── Scheduler jobs + run history (newest runs land first via recordRun timestamps) ──────────
    const seedJob = (j: JobDetail, runs: Array<[string, string, number, number, string]>): void => {
        store.put(space, JOBS_COLL, j.name, j);
        for (const [trigger, status, startedMin, durationMs, message] of runs) {
            recordRun(store, space, j.name, trigger, status, min(startedMin), durationMs, message);
        }
    };
    seedJob(
        { name: 'cdr_ingest_daily', type: 'ingest', cron: '0 0 6 * * *', onPipeline: null, enabled: true, lastStatus: 'RUNNING', lastRunTime: iso(-5), nextFire: iso(60 * 18), catchUp: true, params: { source: 'cdr_sftp_prod', scope: 'roaming' } },
        [['CRON', 'RUNNING', -5, 0, 'Discovering files on cdr_sftp_prod…'], ['CRON', 'SUCCESS', -60 * 24, 142_000, 'Ingested 1,204 files.'], ['CRON', 'SUCCESS', -60 * 48, 138_500, 'Ingested 1,180 files.']],
    );
    seedJob(
        { name: 'enrich_roaming', type: 'enrich', cron: null, onPipeline: 'cdr_ingest', enabled: true, lastStatus: 'SUCCESS', lastRunTime: iso(-30), nextFire: null, params: { task: 'roaming_enrichment' } },
        [['EVENT', 'SUCCESS', -30, 9_200, 'Enriched 1,204 rows.'], ['EVENT', 'SUCCESS', -60 * 24, 8_900, 'Enriched 1,180 rows.']],
    );
    seedJob(
        { name: 'daily_summary_report', type: 'report', cron: '0 30 6 * * *', onPipeline: null, enabled: true, lastStatus: 'FAILED', lastRunTime: iso(-60 * 6), nextFire: iso(60 * 18), params: { report: 'daily_summary', store: 'reports' } },
        [['CRON', 'FAILED', -60 * 6, 4_300, 'Report query failed: store "reports" not found.'], ['CRON', 'SUCCESS', -60 * 30, 5_100, 'Wrote daily_summary.parquet.']],
    );
    seedJob(
        { name: 'catalog_maintenance', type: 'maintenance', cron: '0 0 2 * * 0', onPipeline: null, enabled: true, lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 50), nextFire: iso(60 * 110), catchUp: true, params: { task: 'vacuum' } },
        [['CRON', 'SUCCESS', -60 * 50, 61_000, 'Vacuumed 12 tables.']],
    );
    seedJob(
        { name: 'weekly_billing', type: 'report', cron: '0 0 1 * * 1', onPipeline: null, enabled: false, lastStatus: 'SUCCESS', lastRunTime: iso(-60 * 24 * 5), nextFire: null, params: { report: 'billing' } },
        [['CRON', 'SUCCESS', -60 * 24 * 5, 22_000, 'Wrote billing.csv.']],
    );
    seedJob(
        { name: 'adhoc_export', type: 'flow', cron: null, onPipeline: null, enabled: true, lastStatus: undefined, lastRunTime: undefined, nextFire: null, params: { flow: 'cdr_export' } },
        [],
    );

    // ── Operational events (30, newest first by ts) ─────────────────────────────────────────────
    const pipelines = ['cdr_ingest', 'subscriber_load', 'voucher_etl'];
    for (let i = 0; i < 30; i++) {
        const ts = now - i * 600_000;
        const type = ['BATCH_COMMITTED', 'FILE_RECEIVED', 'FILE_QUARANTINED', 'BATCH_FAILED', 'ALERT_FIRED', 'JOB_SUCCEEDED'][i % 6];
        const pipeline = pipelines[i % 3];
        const event: EventRow = {
            eventId: 'evt-' + (1000 + i),
            ts,
            timestamp: new Date(ts).toISOString(),
            level: ['INFO', 'INFO', 'INFO', 'WARN', 'ERROR'][i % 5],
            type,
            source: 'engine',
            pipeline,
            correlationId: i % 4 === 0 ? 'corr-' + (i % 5) : null,
            message: `${type} on ${pipeline}`,
            attributes: { rows: String((i * 137) % 5000), node: 'node-' + (i % 3) },
        };
        store.put(space, EVENTS_COLL, event.eventId, event);
    }

    // ── Fired alerts (12) + the rules that fired them ───────────────────────────────────────────
    for (let i = 0; i < 12; i++) {
        const alert: FiredAlert = {
            rule: ['high_error_rate', 'rejected_spike', 'slow_batch'][i % 3],
            severity: ['INFO', 'WARNING', 'CRITICAL'][i % 3],
            pipeline: pipelines[i % 3],
            metric: ['error_rate', 'rejected_files', 'duration_ms'][i % 3],
            value: [0.12, 7, 45_000][i % 3] + i,
            comparator: 'gt',
            threshold: [0.1, 5, 30_000][i % 3],
            window: '15m',
            epochMillis: now - i * 900_000,
            message: 'threshold exceeded',
        };
        store.put(space, FIRED_ALERTS_COLL, `alert-${1000 + i}`, alert);
    }
    const rules: AlertRule[] = [
        { name: 'high_error_rate', metric: 'error_rate', comparator: 'gt', threshold: 0.1, window: '15m', severity: 'CRITICAL' },
        { name: 'rejected_spike', metric: 'rejected_files', comparator: 'gt', threshold: 5, window: '1h', severity: 'WARNING' },
        { name: 'slow_batch', metric: 'duration_ms', comparator: 'gt', threshold: 30_000, window: '15m', severity: 'WARNING' },
    ];
    for (const r of rules) store.put(space, ALERT_RULES_COLL, r.name, r);

    // ── Operational objects (Alerts / Incidents / Cases, 15) ────────────────────────────────────
    for (let i = 0; i < 15; i++) {
        const objectType = ['ALERT', 'INCIDENT', 'CASE'][i % 3];
        const status = ['OPEN', 'ACK', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'][i % 5];
        const ts = now - i * 3_600_000;
        const obj: OperationalObject = {
            id: objectType.toLowerCase() + '-' + (100 + i),
            objectType,
            title: `${objectType} ${100 + i}: ${['error rate', 'rejected files', 'slow batch'][i % 3]}`,
            description: 'auto-generated sample',
            status,
            severity: ['INFO', 'WARNING', 'CRITICAL'][i % 3],
            priority: ['LOW', 'MEDIUM', 'HIGH'][i % 3],
            owner: 'ops',
            assignee: i % 2 ? 'alice' : 'bob',
            correlationId: 'corr-' + (i % 5),
            attributes: { pipeline: pipelines[i % 3] },
            createdAt: ts,
            updatedAt: ts + 600_000,
            closedAt: status === 'CLOSED' ? ts + 1_200_000 : 0,
        };
        store.put(space, OPS_OBJECTS_COLL, obj.id, obj);
    }

    // ── Enrichment jobs ─────────────────────────────────────────────────────────────────────────
    const enrich: EnrichmentJobView[] = [
        { name: 'events_daily_kpi', onPipeline: 'events', eventTriggered: true, runCount: 42, lastRunStatus: 'SUCCESS', lastRunTime: new Date(now).toISOString() },
        { name: 'subscriber_rollup', scheduleTriggered: true, runCount: 18, lastRunStatus: 'SUCCESS' },
    ];
    for (const e of enrich) store.put(space, ENRICHMENT_COLL, e.name, e);

    // ── Connection profiles (workbench + list navigable with no backend) ────────────────────────
    const connections: ConnectionProfile[] = [
        {
            id: 'cdr_sftp_prod', connector: 'sftp', host: 'sftp.example.com', port: 22, basePath: '/cdr/outbox',
            username: 'cdruser', password: '${ENV:CDR_SFTP_PASSWORD}', options: { auth_method: 'key' },
            tunnel: { host: 'bastion.example.com', port: 22, username: 'jump', password: '${ENV:BASTION_PASSWORD}' },
        },
        { id: 'pg_warehouse', connector: 'db', host: 'pg.example.com', port: 5432, database: 'warehouse', username: 'etl', password: '${ENV:PG_PASSWORD}', options: { sslmode: 'require' } },
        { id: 's3_archive', connector: 's3', host: 's3.amazonaws.com', basePath: 'cdr-archive', options: { region: 'eu-west-1' } },
        { id: 'local_inbox', connector: 'local', basePath: '/data/inbox' },
        { id: 'legacy_ftp_down', connector: 'ftp', host: 'ftp.legacy.example.com', port: 21, username: 'ops', password: '${ENV:FTP_PW}' },
    ];
    for (const c of connections) store.put(space, CONNECTIONS_COLL, c.id, c);

    // ── Notifications ───────────────────────────────────────────────────────────────────────────
    for (const notif of seedNotifications(now)) store.put(space, NOTIFICATIONS_COLL, notif.id, notif);
}
