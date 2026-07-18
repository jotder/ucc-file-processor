import { MockFlags } from '../mock-flags';
import { json, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * Mock for the navigation-menus backend (`NavRoutes`, GET/PUT `/nav/menus`) — a per-space singleton
 * document, so the Menu Builder + custom sidebar work with no backend at all (`mockDemo`). Mirrors the
 * server's whole-document replace and `space`-stamped response; node canonicalization is trusted to the
 * client (the real backend re-validates). When flags leave this off, `/nav/menus` falls through to the
 * real `NavRoutes` — the default in the shared dev preview.
 */

export const NAV_COLL = 'nav';
const MENUS = /\/nav\/menus$/;

/** An explicit `/spaces/<id>/nav/menus` call targets that space; otherwise the active space applies. */
const spaceOf = (url: string, active: string): string => url.match(/\/spaces\/([^/]+)\/nav\//)?.[1] ?? active;

interface MenusDoc {
    id: string;
    version: 1;
    nodes: unknown[];
}

export function navHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockDemo) return undefined;
        if (!MENUS.test(req.url)) return undefined;
        const space = spaceOf(req.url, req.space);

        if (req.method === 'GET') {
            const doc = store.get<MenusDoc>(space, NAV_COLL, 'menus');
            return json({ space, version: 1, nodes: doc?.nodes ?? [] });
        }
        if (req.method === 'PUT') {
            const body = (req.body ?? {}) as { nodes?: unknown[] };
            const doc: MenusDoc = { id: 'menus', version: 1, nodes: Array.isArray(body.nodes) ? body.nodes : [] };
            store.put(space, NAV_COLL, doc.id, doc);
            return json({ space, version: 1, nodes: doc.nodes });
        }
        return undefined;
    };
}
