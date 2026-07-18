import { describe, expect, it } from 'vitest';
import { MockFlags } from '../mock-flags';
import { MockStore } from '../mock-store';
import { navHandler } from './nav.handler';

const flags = { mockDemo: true } as MockFlags;

function req(method: string, url: string, body: unknown = null) {
    return { method, url, body, params: {}, space: 's1' };
}

const NODE = { id: 'm1', title: 'Revenue', children: [] };

describe('navHandler', () => {
    it('GET /nav/menus returns an empty, space-stamped tree before any save', () => {
        const res = navHandler(flags)(req('GET', '/nav/menus'), new MockStore());
        expect(res?.body).toEqual({ space: 's1', version: 1, nodes: [] });
    });

    it('PUT round-trips the nodes and echoes the space-stamped tree', () => {
        const store = new MockStore();
        const handle = navHandler(flags);
        const put = handle(req('PUT', '/nav/menus', { version: 1, nodes: [NODE] }), store);
        expect(put?.body).toEqual({ space: 's1', version: 1, nodes: [NODE] });
        expect(handle(req('GET', '/nav/menus'), store)?.body).toEqual({ space: 's1', version: 1, nodes: [NODE] });
    });

    it('PUT tolerates a missing/invalid nodes list (folds to empty)', () => {
        const store = new MockStore();
        const res = navHandler(flags)(req('PUT', '/nav/menus', {}), store);
        expect(res?.body).toEqual({ space: 's1', version: 1, nodes: [] });
    });

    it('scopes the tree to the space id in the URL, not just the active space', () => {
        const store = new MockStore();
        const handle = navHandler(flags);
        handle(req('PUT', '/api/v1/spaces/beta/nav/menus', { version: 1, nodes: [NODE] }), store);
        expect(handle(req('GET', '/nav/menus'), store)?.body).toMatchObject({ space: 's1', nodes: [] });
        expect(handle(req('GET', '/api/v1/spaces/beta/nav/menus'), store)?.body).toMatchObject({ space: 'beta', nodes: [NODE] });
    });

    it('stays out of the way when the demo mock is off (falls through to the real backend)', () => {
        const res = navHandler({ mockDemo: false } as MockFlags)(req('GET', '/nav/menus'), new MockStore());
        expect(res).toBeUndefined();
    });
});
