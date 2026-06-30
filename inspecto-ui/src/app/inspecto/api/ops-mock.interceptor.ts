import { HttpEvent, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { AlertRule, FiredAlert } from './alerts.service';
import { EventRow } from './events.service';
import { AuditRow, EnrichmentJobView } from './models';
import { OperationalObject } from './objects.service';

/**
 * PROTOTYPE-ONLY offline mock for the operational-intelligence surfaces (events · alerts · objects ·
 * enrichment), so the reusable query panel can be exercised over real-shaped rows with no backend. Serves
 * the list endpoints each page fetches on init; user-triggered mutations (transition/comment/save view)
 * still pass through. Gated on {@code environment.mockOps}; registered before the space/error interceptors
 * (it matches path SUFFIXES so the space prefix is irrelevant). Flip the flag / remove once these surfaces
 * are wired to the real backend.
 */
const EVENTS_SEARCH = /\/events\/search$/;
const EVENTS_VIEWS = /\/events\/views$/;
const ALERTS_RULES = /\/alerts\/rules$/;
const ALERTS_EVAL = /\/alerts\/evaluate$/;
const ALERTS = /\/alerts$/;
const OBJECTS = /\/objects$/;
const ENRICH_RUNS = /\/enrichment\/([^/]+)\/runs$/;
const ENRICH_LINEAGE = /\/enrichment\/([^/]+)\/lineage$/;
const ENRICH_LIST = /\/enrichment$/;
const LATENCY_MS = 200;

const NOW = Date.now();
const PIPELINES = ['cdr_ingest', 'subscriber_load', 'voucher_etl'];

const EVENTS: EventRow[] = Array.from({ length: 30 }, (_, i) => {
    const level = ['INFO', 'INFO', 'INFO', 'WARN', 'ERROR'][i % 5];
    const type = ['BATCH_COMMITTED', 'FILE_RECEIVED', 'FILE_QUARANTINED', 'BATCH_FAILED', 'ALERT_FIRED', 'JOB_SUCCEEDED'][i % 6];
    const pipeline = PIPELINES[i % 3];
    const ts = NOW - i * 600_000;
    return {
        eventId: 'evt-' + (1000 + i),
        ts,
        timestamp: new Date(ts).toISOString(),
        level,
        type,
        source: 'engine',
        pipeline,
        correlationId: i % 4 === 0 ? 'corr-' + (i % 5) : null,
        message: `${type} on ${pipeline}`,
        attributes: { rows: String((i * 137) % 5000), node: 'node-' + (i % 3) },
    };
});

const ALERTS_DATA: FiredAlert[] = Array.from({ length: 12 }, (_, i) => ({
    rule: ['high_error_rate', 'rejected_spike', 'slow_batch'][i % 3],
    severity: ['INFO', 'WARNING', 'CRITICAL'][i % 3],
    pipeline: PIPELINES[i % 3],
    metric: ['error_rate', 'rejected_files', 'duration_ms'][i % 3],
    value: [0.12, 7, 45_000][i % 3] + i,
    comparator: 'gt',
    threshold: [0.1, 5, 30_000][i % 3],
    window: '15m',
    epochMillis: NOW - i * 900_000,
    message: 'threshold exceeded',
}));

const ALERT_RULES: AlertRule[] = [
    { name: 'high_error_rate', metric: 'error_rate', comparator: 'gt', threshold: 0.1, window: '15m', severity: 'CRITICAL' },
    { name: 'rejected_spike', metric: 'rejected_files', comparator: 'gt', threshold: 5, window: '1h', severity: 'WARNING' },
    { name: 'slow_batch', metric: 'duration_ms', comparator: 'gt', threshold: 30_000, window: '15m', severity: 'WARNING' },
];

const OBJECTS_DATA: OperationalObject[] = Array.from({ length: 15 }, (_, i) => {
    const objectType = ['ALERT', 'INCIDENT', 'CASE'][i % 3];
    const status = ['OPEN', 'ACK', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'][i % 5];
    const ts = NOW - i * 3_600_000;
    return {
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
        attributes: { pipeline: PIPELINES[i % 3] },
        createdAt: ts,
        updatedAt: ts + 600_000,
        closedAt: status === 'CLOSED' ? ts + 1_200_000 : 0,
    };
});

const ENRICH_JOBS: EnrichmentJobView[] = [
    { name: 'events_daily_kpi', onPipeline: 'events', eventTriggered: true, runCount: 42, lastRunStatus: 'SUCCESS', lastRunTime: new Date(NOW).toISOString() },
    { name: 'subscriber_rollup', scheduleTriggered: true, runCount: 18, lastRunStatus: 'SUCCESS' },
];

function enrichRuns(job: string): AuditRow[] {
    return Array.from({ length: 20 }, (_, i) => ({
        run_id: job + '-r' + (1000 + i),
        status: i % 7 === 0 ? 'FAILED' : 'SUCCESS',
        output_rows: String((i * 311) % 9000),
        duration_ms: String(800 + ((i * 53) % 5000)),
        run_ts: new Date(NOW - i * 86_400_000).toISOString(),
    }));
}

export const opsMockInterceptor: HttpInterceptorFn = (req, next) => {
    if (!(environment as { mockOps?: boolean }).mockOps) return next(req);

    let m: RegExpMatchArray | null;
    if (req.method === 'GET' && EVENTS_SEARCH.test(req.url)) return reply(EVENTS);
    if (req.method === 'GET' && EVENTS_VIEWS.test(req.url)) return reply([]);
    if (req.method === 'POST' && EVENTS_VIEWS.test(req.url)) {
        const body = (req.body as { name?: string }) ?? {};
        return reply({ name: body.name ?? 'view', filters: {}, createdAt: NOW });
    }
    if (req.method === 'GET' && ALERTS_RULES.test(req.url)) return reply(ALERT_RULES);
    if (req.method === 'POST' && ALERTS_EVAL.test(req.url)) return reply([]);
    if (req.method === 'GET' && ALERTS.test(req.url)) return reply(ALERTS_DATA);
    if (req.method === 'GET' && OBJECTS.test(req.url)) {
        const type = req.params.get('type');
        return reply(type ? OBJECTS_DATA.filter((o) => o.objectType === type.toUpperCase()) : OBJECTS_DATA);
    }
    if (req.method === 'GET' && (m = req.url.match(ENRICH_RUNS))) return reply(enrichRuns(decodeURIComponent(m[1])));
    if (req.method === 'GET' && (m = req.url.match(ENRICH_LINEAGE))) return reply(enrichRuns(decodeURIComponent(m[1])));
    if (req.method === 'GET' && ENRICH_LIST.test(req.url)) return reply(ENRICH_JOBS);

    return next(req);
};

function reply<T>(body: T): Observable<HttpEvent<unknown>> {
    return of(new HttpResponse({ status: 200, body })).pipe(delay(LATENCY_MS));
}
