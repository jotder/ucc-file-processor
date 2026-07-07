import { describe, expect, it } from 'vitest';
import type { Space } from '../../api/spaces.service';
import type { SpaceTemplateInfo } from '../../api/spaces.service';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { componentCollection } from './components.handler';
import { PIPELINES_COLL } from './pipelines.handler';
import { spacesHandler, SERVER_SPACE, SPACES_COLL } from './spaces.handler';

const req = (method: string, url: string, body: unknown = null, params: Record<string, string> = {}): MockRequest => ({
    method,
    url,
    body,
    params,
    space: 'default',
});

describe('spacesHandler', () => {
    const handler = spacesHandler({ mockSpaces: true });

    it('is inert when the flag is off', () => {
        expect(spacesHandler({})(req('GET', '/api/spaces'), new MockStore())).toBeUndefined();
    });

    it('reports a multi-space server and always lists the default space', () => {
        const store = new MockStore();
        expect(handler(req('GET', '/api/spaces/_meta'), store)?.body).toEqual({ multiSpace: true });
        const list = handler(req('GET', '/api/spaces'), store)?.body as Space[];
        expect(list.map((s) => s.id)).toEqual(['default']);
    });

    it('serves the template catalog without seed functions', () => {
        const list = handler(req('GET', '/api/spaces/templates'), new MockStore())?.body as SpaceTemplateInfo[];
        expect(list.map((t) => t.id)).toEqual(['telecom-ra', 'fraud-mgmt', 'financial-audit', 'link-analysis']);
        for (const t of list) {
            expect(t.name).toBeTruthy();
            expect(t.contents.length).toBeGreaterThan(0);
            expect((t as unknown as Record<string, unknown>)['seed']).toBeUndefined();
        }
    });

    it('creates an EMPTY space that stays empty (no default pack lands on first use)', () => {
        const store = new MockStore();
        const created = handler(req('POST', '/api/spaces', { id: 'blank', display_name: 'Blank' }), store);
        expect(created?.status ?? 200).toBe(200);
        expect((created?.body as Space).displayName).toBe('Blank');
        // A later ensureSeeded (what the interceptor does per request) must be a no-op.
        store.ensureSeeded('blank', () => {
            throw new Error('empty space must already be marked seeded');
        });
        expect(store.list('blank', PIPELINES_COLL)).toEqual([]);
    });

    it('creates a space FROM A TEMPLATE, fully seeded with the blueprint', () => {
        const store = new MockStore();
        const created = handler(req('POST', '/api/spaces', { id: 'ra-prod', template: 'telecom-ra' }), store);
        expect(created?.status ?? 200).toBe(200);
        expect((created?.body as Space).displayName).toBe('Telecom Revenue Assurance'); // template name as default
        // The blueprint landed: pipelines, datasets, the reconciliation, a dashboard.
        const pipelines = store.list<{ name: string }>('ra-prod', PIPELINES_COLL).map((p) => p.name);
        expect(pipelines).toContain('switch_cdr_ingest');
        expect(store.has('ra-prod', componentCollection('reconciliation'), 'switch_vs_billing')).toBe(true);
        expect(store.has('ra-prod', componentCollection('dashboard'), 'ra_overview')).toBe(true);
        // …and the datasources endpoint reflects it.
        const ds = handler(req('GET', '/api/spaces/ra-prod/datasources'), store)?.body as string[];
        expect(ds).toContain('billing_rated_load');
    });

    it('rejects a bad id (400), a duplicate (409) and an unknown template (400)', () => {
        const store = new MockStore();
        expect(handler(req('POST', '/api/spaces', { id: 'Bad_ID!' }), store)?.status).toBe(400);
        expect(handler(req('POST', '/api/spaces', { id: 'default' }), store)?.status).toBe(409);
        expect(handler(req('POST', '/api/spaces', { id: 'ok', template: 'nope' }), store)?.status).toBe(400);
    });

    it('deletes a space (registry + data), 404s the unknown, refuses the default', () => {
        const store = new MockStore();
        handler(req('POST', '/api/spaces', { id: 'gone', template: 'fraud-mgmt' }), store);
        expect(store.list('gone', PIPELINES_COLL).length).toBeGreaterThan(0);

        const res = handler(req('DELETE', '/api/spaces/gone', null, { purge: 'true' }), store);
        expect(res?.body).toEqual({ id: 'gone', deleted: true, purged: true });
        expect(store.has(SERVER_SPACE, SPACES_COLL, 'gone')).toBe(false);
        // clearSpace dropped the data — a re-created space re-seeds from scratch.
        store.ensureSeeded('gone', (s, sp) => s.put(sp, 'probe', 'p1', {}));
        expect(store.has('gone', 'probe', 'p1')).toBe(true);

        expect(handler(req('DELETE', '/api/spaces/unknown'), store)?.status).toBe(404);
        expect(handler(req('DELETE', '/api/spaces/default'), store)?.status).toBe(400);
    });

    it('updates a space name/description, 404s the unknown, refuses the default', () => {
        const store = new MockStore();
        handler(req('POST', '/api/spaces', { id: 'acme', display_name: 'Acme' }), store);
        const res = handler(req('PUT', '/api/spaces/acme', { display_name: 'Acme Corp', description: 'renamed' }), store);
        expect((res?.body as Space).displayName).toBe('Acme Corp');
        expect((res?.body as Space).description).toBe('renamed');
        expect((res?.body as Space).id).toBe('acme'); // id immutable
        expect(handler(req('PUT', '/api/spaces/unknown', { display_name: 'x' }), store)?.status).toBe(404);
        expect(handler(req('PUT', '/api/spaces/default', { display_name: 'x' }), store)?.status).toBe(400);
    });

    it('404s datasources for an unknown space', () => {
        expect(handler(req('GET', '/api/spaces/nope/datasources'), new MockStore())?.status).toBe(404);
    });
});
