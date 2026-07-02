import type {
    CheckOutcome,
    ConnectionProbeResult,
    ResourceKind,
    ResourceNode,
    SampleResult,
} from '../../api/connection-probe.service';
import type { ConnectionProfile, ConnectionTestResult } from '../../api/connections.service';
import { MockFlags } from '../mock-flags';
import { json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The connection-workbench mock domain (connect · explore · test · sample) — the port of the old
 * `connection-mock` interceptor onto the {@link MockStore} (the profile list is now seeded per
 * space; probe/explore/sample stay pure canned compute against the frozen contract). Real
 * `/connections` CRUD (POST/PUT/DELETE) still passes through — B2 wires the real library.
 *
 * <p>Demo affordances: name a connection containing {@code down} to see a failing probe; one containing a
 * DB hint ({@code db/pg/postgres/sql}) explores schemas/tables/columns instead of files; {@code s3/gcs/http}
 * marks the WRITE check skipped (read-only).
 */

export const CONNECTIONS_COLL = 'connection';

const PROBE = /\/connections\/([^/]+)\/probe$/;
const EXPLORE = /\/connections\/([^/]+)\/explore$/;
const SAMPLE = /\/connections\/([^/]+)\/sample$/;
const PROFILE_TEST = /\/connections\/test$/;
const TEST = /\/connections\/([^/]+)\/test$/;
const DETAIL = /\/connections\/([^/]+)$/;
const LIST = /\/connections$/;

export function connectionsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockConnectionProbe) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'POST' && (m = match(url, PROBE))) return json(mockProbe(m[1]));
        if (method === 'GET' && (m = match(url, EXPLORE))) return json(mockExplore(m[1], req.params['path'] ?? ''));
        if (method === 'GET' && match(url, SAMPLE)) {
            const limit = Number(req.params['limit'] ?? 50);
            return json(mockSample(req.params['path'] ?? '', limit));
        }
        if (method === 'POST' && PROFILE_TEST.test(url)) {
            return json(mockProfileTest(req.body as Partial<ConnectionProfile> | null, req.params['target'] ?? 'connection'));
        }
        if (method === 'POST' && (m = match(url, TEST))) return json(mockTest(m[1]));
        if (method === 'GET' && (m = match(url, DETAIL))) {
            const id = m[1];
            return json(
                store.get<ConnectionProfile>(space, CONNECTIONS_COLL, id) ??
                    { id, connector: connectorOf(id), host: 'host.example.com', port: 22 },
            );
        }
        if (method === 'GET' && LIST.test(url)) return json(store.list<ConnectionProfile>(space, CONNECTIONS_COLL));
        return undefined;
    };
}

function mockProfileTest(p: Partial<ConnectionProfile> | null, target: string): ConnectionTestResult {
    const prof = p ?? {};
    const profile = prof as ConnectionProfile;
    const connector = prof.connector || 'sftp';
    const id = prof.id || '(unsaved)';
    // Pick the endpoint under test: the bastion hop, the proxy, or the connection itself.
    const hop = target === 'tunnel' ? profile.tunnel : target === 'proxy' ? profile.proxy : null;
    const label = target === 'tunnel' ? 'tunnel' : target === 'proxy' ? 'proxy' : '';
    const host = hop ? hop.host : prof.host;
    const port = hop ? hop.port : prof.port;
    if (!hop && connector === 'local') {
        return { id, connector, endpoint: 'local', reachable: true, latencyMs: 0, secretsResolved: true, detail: 'local source — no remote endpoint to test' };
    }
    if (!host) {
        return { id, connector, endpoint: label ? `${label}: (none)` : '(no host)', reachable: false, secretsResolved: true, detail: `no ${label ? label + ' ' : ''}host configured` };
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
