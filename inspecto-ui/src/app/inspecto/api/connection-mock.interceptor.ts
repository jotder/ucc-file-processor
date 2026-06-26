import { HttpEvent, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import {
    CheckOutcome,
    ConnectionProbeResult,
    ResourceKind,
    ResourceNode,
    SampleResult,
} from './connection-probe.service';
import { ConnectionProfile, ConnectionTestResult } from './connections.service';

/**
 * PROTOTYPE-ONLY mock for the connection workbench (connect · explore · test · sample). Serves canned
 * responses for the three not-yet-built endpoints — `POST /connections/{id}/probe`,
 * `GET /connections/{id}/explore`, `GET /connections/{id}/sample` — so the UI is built and reviewed
 * UI-first against the frozen contract. Everything else (incl. real `/connections` CRUD + legacy
 * `/test`) passes straight through. Gated on {@code environment.mockConnectionProbe}; registered FIRST so
 * it short-circuits before the space/error interceptors. Remove (or flip the flag) once B2 wires the real
 * library behind these routes.
 *
 * <p>Demo affordances: name a connection containing {@code down} to see a failing probe; one containing a
 * DB hint ({@code db/pg/postgres/sql}) explores schemas/tables/columns instead of files; {@code s3/gcs/http}
 * marks the WRITE check skipped (read-only).
 */
const PROBE = /\/connections\/([^/]+)\/probe$/;
const EXPLORE = /\/connections\/([^/]+)\/explore$/;
const SAMPLE = /\/connections\/([^/]+)\/sample$/;
const PROFILE_TEST = /\/connections\/test$/;
const TEST = /\/connections\/([^/]+)\/test$/;
const DETAIL = /\/connections\/([^/]+)$/;
const LIST = /\/connections$/;
const LATENCY_MS = 250;

/** A handful of profiles so the list + workbench are fully navigable with no backend (prototype). */
const MOCK_CONNECTIONS: ConnectionProfile[] = [
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

export const connectionMockInterceptor: HttpInterceptorFn = (req, next) => {
    if (!(environment as { mockConnectionProbe?: boolean }).mockConnectionProbe) return next(req);

    let m: RegExpMatchArray | null;
    if (req.method === 'POST' && (m = req.url.match(PROBE))) {
        return reply(mockProbe(decodeURIComponent(m[1])));
    }
    if (req.method === 'GET' && (m = req.url.match(EXPLORE))) {
        return reply(mockExplore(decodeURIComponent(m[1]), req.params.get('path') ?? ''));
    }
    if (req.method === 'GET' && (m = req.url.match(SAMPLE))) {
        const limit = Number(req.params.get('limit') ?? 50);
        return reply(mockSample(req.params.get('path') ?? '', limit));
    }
    if (req.method === 'POST' && PROFILE_TEST.test(req.url)) {
        return reply(mockProfileTest(req.body as Partial<ConnectionProfile> | null, req.params.get('target') ?? 'connection'));
    }
    if (req.method === 'POST' && (m = req.url.match(TEST))) {
        return reply(mockTest(decodeURIComponent(m[1])));
    }
    if (req.method === 'GET' && (m = req.url.match(DETAIL))) {
        return reply(mockDetail(decodeURIComponent(m[1])));
    }
    if (req.method === 'GET' && LIST.test(req.url)) {
        return reply(MOCK_CONNECTIONS);
    }
    return next(req);
};

function mockDetail(id: string): ConnectionProfile {
    return MOCK_CONNECTIONS.find((c) => c.id === id) ?? { id, connector: connectorOf(id), host: 'host.example.com', port: 22 };
}

function mockProfileTest(p: Partial<ConnectionProfile> | null, target: string): ConnectionTestResult {
    const prof = p ?? {};
    const isTunnel = target === 'tunnel';
    const tunnel = (prof as ConnectionProfile).tunnel;
    const host = isTunnel ? tunnel?.host : prof.host;
    const port = isTunnel ? tunnel?.port : prof.port;
    const connector = prof.connector || 'sftp';
    const id = prof.id || '(unsaved)';
    if (!isTunnel && connector === 'local') {
        return { id, connector, endpoint: 'local', reachable: true, latencyMs: 0, secretsResolved: true, detail: 'local source — no remote endpoint to test' };
    }
    if (!host) {
        return { id, connector, endpoint: isTunnel ? 'tunnel: (none)' : '(no host)', reachable: false, secretsResolved: true, detail: `no ${isTunnel ? 'tunnel ' : ''}host configured` };
    }
    const reachable = !String(host).toLowerCase().includes('down');
    return { id, connector, endpoint: `${host}:${port ?? '?'}`, reachable, latencyMs: reachable ? 14 : undefined, secretsResolved: true, detail: reachable ? 'TCP connect ok' : 'connect timed out' };
}

function mockTest(id: string): ConnectionTestResult {
    const reachable = !id.toLowerCase().includes('down');
    return {
        id,
        connector: connectorOf(id),
        endpoint: 'host.example.com:22',
        reachable,
        latencyMs: reachable ? 12 : undefined,
        secretsResolved: true,
        detail: reachable ? 'TCP connect ok' : 'connect timed out',
    };
}

function reply<T>(body: T): Observable<HttpEvent<unknown>> {
    return of(new HttpResponse({ status: 200, body })).pipe(delay(LATENCY_MS));
}

function connectorOf(id: string): string {
    const l = id.toLowerCase();
    if (/db|pg|postgres|sql|mysql|oracle/.test(l)) return 'db';
    if (/s3/.test(l)) return 's3';
    if (/gcs/.test(l)) return 'gcs';
    if (/ftp/.test(l)) return 'ftp';
    return 'sftp';
}

function mockProbe(id: string): ConnectionProbeResult {
    const l = id.toLowerCase();
    const reachable = !l.includes('down');
    const readOnly = l.includes('-ro') || l.includes('readonly') || /s3|gcs|https?/.test(l);
    const checks: CheckOutcome[] = [
        { check: 'reachability', ok: reachable, detail: reachable ? 'TCP connect ok' : 'connect timed out', latencyMs: reachable ? 12 : undefined },
        { check: 'authenticate', ok: reachable, skipped: !reachable, detail: reachable ? 'auth handshake ok' : 'not attempted (unreachable)', latencyMs: reachable ? 38 : undefined },
        { check: 'read', ok: reachable, skipped: !reachable, detail: reachable ? 'base path readable' : 'not attempted' },
        { check: 'write', ok: reachable && !readOnly, skipped: !reachable || readOnly, detail: readOnly ? 'connector is read-only' : reachable ? 'scratch write + delete ok' : 'not attempted' },
        { check: 'list', ok: reachable, skipped: !reachable, detail: reachable ? 'listed 7 entries' : 'not attempted' },
    ];
    return {
        id,
        connector: connectorOf(id),
        endpoint: 'host.example.com:22',
        ok: checks.every((c) => c.skipped || c.ok),
        secretsResolved: true,
        checks,
    };
}

function mockExplore(id: string, path: string): ResourceNode[] {
    const isDb = /db|pg|postgres|sql|mysql|oracle/i.test(id);
    const segs = path.split('/').filter(Boolean);
    const child = (name: string, kind: ResourceKind, hasChildren: boolean, extra: Partial<ResourceNode> = {}): ResourceNode => ({
        name,
        path: path ? `${path}/${name}` : name,
        kind,
        hasChildren,
        readable: true,
        ...extra,
    });
    if (isDb) {
        if (segs.length === 0) return [child('public', 'schema', true), child('staging', 'schema', true)];
        if (segs.length === 1) return [child('calls', 'table', true), child('subscribers', 'table', true)];
        return ['id', 'msisdn', 'start_time', 'duration_s', 'cell_id'].map((c) => child(c, 'column', false));
    }
    if (segs.length === 0)
        return [
            child('inbox', 'dir', true, { writable: true }),
            child('archive', 'dir', true),
            child('README.txt', 'file', false, { sizeBytes: 482, modifiedAt: '2026-06-20T09:14:00Z' }),
        ];
    if (segs.length === 1)
        return [
            child('2026-06', 'dir', true),
            child('feed_001.csv.gz', 'file', false, { sizeBytes: 1_482_104, modifiedAt: '2026-06-23T02:00:00Z' }),
            child('feed_002.csv.gz', 'file', false, { sizeBytes: 1_502_882, modifiedAt: '2026-06-24T02:00:00Z' }),
        ];
    return [
        child('part-0001.csv', 'file', false, { sizeBytes: 904_233 }),
        child('part-0002.csv', 'file', false, { sizeBytes: 911_002 }),
    ];
}

function mockSample(path: string, limit: number): SampleResult {
    const columns = ['id', 'msisdn', 'start_time', 'duration_s', 'cell_id'];
    const total = 1287;
    const n = Math.min(Math.max(limit, 1), 200);
    const rows = Array.from({ length: n }, (_, i) => ({
        id: 1000 + i,
        msisdn: '8801' + String(700000000 + i),
        start_time: `2026-06-24 0${i % 9}:${String(10 + (i % 50)).padStart(2, '0')}:00`,
        duration_s: (i * 37) % 600,
        cell_id: 'CELL-' + (100 + (i % 12)),
    }));
    return { path, columns, rows, truncated: total > rows.length, detail: `showing ${rows.length} of ~${total} rows` };
}
