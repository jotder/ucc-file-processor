import { describe, expect, it } from 'vitest';
import type { ConnectionProfile } from '../../api/connections.service';
import { registerIntegrityRules } from '../integrity';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { connectionsHandler } from './connections.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    registerIntegrityRules(store);
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

describe('connectionsHandler', () => {
    const handler = connectionsHandler({ mockConnectionProbe: true });

    it('lists seeded profiles and creates a new one (409 on duplicate)', () => {
        const store = seededStore();
        const list = handler(req('GET', '/api/connections'), store)?.body as ConnectionProfile[];
        expect(list.map((c) => c.id)).toContain('cdr_sftp_prod');

        const created = handler(
            req('POST', '/api/connections', { id: 'new_sftp', connector: 'sftp', host: 'h', description: 'test box' }),
            store,
        );
        expect(created?.status ?? 200).toBe(200);
        expect((created?.body as ConnectionProfile).description).toBe('test box');

        const dup = handler(req('POST', '/api/connections', { id: 'new_sftp', connector: 'sftp' }), store);
        expect(dup?.status).toBe(409);
    });

    it('updates a profile preserving *** secrets; 404 on unknown', () => {
        const store = seededStore();
        const updated = handler(
            req('PUT', '/api/connections/pg_warehouse', {
                id: 'pg_warehouse', connector: 'db', host: 'pg2.example.com', password: '***',
            }),
            store,
        );
        const p = updated?.body as ConnectionProfile;
        expect(p.host).toBe('pg2.example.com');
        expect(p.password).toBe('${ENV:PG_PASSWORD}'); // '***' kept the stored secret

        expect(handler(req('PUT', '/api/connections/nope', { id: 'nope' }), store)?.status).toBe(404);
    });

    it('409s deleting a connection an authored pipeline still uses', () => {
        const store = seededStore();
        // Seeded pipeline cdr_ingest binds `use: 'connections/cdr_sftp_prod'`.
        const res = handler(req('DELETE', '/api/connections/cdr_sftp_prod'), store);
        expect(res?.status).toBe(409);
        expect(String((res?.body as { error: string }).error)).toContain('cdr_ingest');
        expect(store.has('default', 'connection', 'cdr_sftp_prod')).toBe(true);
    });

    it('deletes an unreferenced connection; 404 on unknown', () => {
        const store = seededStore();
        expect(handler(req('DELETE', '/api/connections/local_inbox'), store)?.body).toEqual({ deleted: true });
        expect(handler(req('DELETE', '/api/connections/local_inbox'), store)?.status).toBe(404);
    });

    it('still serves the canned probe and falls through when flagged off', () => {
        const store = seededStore();
        const probe = handler(req('POST', '/api/connections/legacy_ftp_down/probe'), store)?.body as { ok: boolean };
        expect(probe.ok).toBe(false); // 'down' in the id ⇒ failing probe
        expect(connectionsHandler({})(req('GET', '/api/connections'), store)).toBeUndefined();
    });
});
