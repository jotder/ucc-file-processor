import type { AuthoredPipeline } from '../../api/pipelines.service';
import type { Space } from '../../api/spaces.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { findTemplate, SPACE_TEMPLATES, templateInfo } from '../seeds/templates';
import { PIPELINES_COLL } from './pipelines.handler';

/**
 * Server-global `/spaces` mock domain (W5) — makes the multi-space runtime real in mock mode so the
 * Space-Templates gallery, the header space-switcher and the Spaces admin pane all work with no
 * backend. Mirrors the real ControlApi contract (`/spaces/_meta`, list/create/delete, per-space
 * datasources) and adds the W5 `/spaces/templates` catalog + an optional `template` on POST /spaces
 * that seeds the new space from the chosen seed pack.
 *
 * The space registry itself is server-global, not per-space, so it lives in the reserved `_server`
 * pseudo-space — unreachable as a real space id (ids must start with a letter or digit).
 */

/** Reserved pseudo-space holding server-global mock state (the space registry). */
export const SERVER_SPACE = '_server';
export const SPACES_COLL = 'spaces';

/** The backend's SpaceId jail: lowercase letters/digits/hyphen, not leading with a hyphen, ≤ 63 chars. */
const SPACE_ID = /^[a-z0-9][a-z0-9-]{0,62}$/;

const META = /\/spaces\/_meta$/;
const TEMPLATES = /\/spaces\/templates$/;
const SPACES = /\/spaces$/;
const SPACE_DS = /\/spaces\/([^/]+)\/datasources$/;
const SPACE_ONE = /\/spaces\/([^/]+)$/;

export function spacesHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockSpaces) return undefined;
        const { method, url } = req;
        let m: string[] | null;

        ensureRegistry(store);

        if (method === 'GET' && META.test(url)) return json({ multiSpace: true });
        if (method === 'GET' && TEMPLATES.test(url)) return json(SPACE_TEMPLATES.map(templateInfo));
        if (method === 'GET' && SPACES.test(url)) {
            return json(store.list<Space>(SERVER_SPACE, SPACES_COLL).sort((a, b) => a.id.localeCompare(b.id)));
        }
        if (method === 'POST' && SPACES.test(url)) return createSpace(store, req.body);
        if (method === 'PUT' && (m = match(url, SPACE_ONE))) return updateSpace(store, m[1], req.body);
        if (method === 'GET' && (m = match(url, SPACE_DS))) {
            if (!store.has(SERVER_SPACE, SPACES_COLL, m[1])) return error(404, `Unknown space "${m[1]}".`);
            return json(store.list<AuthoredPipeline>(m[1], PIPELINES_COLL).map((p) => p.name));
        }
        if (method === 'DELETE' && (m = match(url, SPACE_ONE))) return deleteSpace(store, m[1], req.params);
        return undefined;
    };
}

/** POST /spaces — validate the id, reject duplicates, register, and seed (template pack or empty). */
function createSpace(store: MockStore, body: unknown) {
    const b = (body ?? {}) as { id?: string; display_name?: string; description?: string; template?: string };
    const id = String(b.id ?? '');
    if (!SPACE_ID.test(id)) return error(400, 'Invalid space id: use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.');
    if (store.has(SERVER_SPACE, SPACES_COLL, id)) return error(409, `A space "${id}" already exists.`);
    const template = b.template ? findTemplate(b.template) : undefined;
    if (b.template && !template) return error(400, `Unknown space template "${b.template}".`);

    const space: Space = {
        id,
        displayName: b.display_name || template?.name || id,
        description: b.description || template?.tagline || '',
        createdAt: new Date().toISOString(),
    };
    store.put(SERVER_SPACE, SPACES_COLL, id, space);
    // A template seeds its full blueprint; an empty space is marked seeded so the default pack
    // doesn't land in it on first use (the point of "New space" is a blank slate).
    store.ensureSeeded(id, template ? template.seed : () => undefined);
    return json(space);
}

/** PUT /spaces/{id} — update a space's display name / description (the id/folder is immutable). */
function updateSpace(store: MockStore, id: string, body: unknown) {
    if (id === 'default') return error(400, 'The default space cannot be edited.');
    const existing = store.get<Space>(SERVER_SPACE, SPACES_COLL, id);
    if (!existing) return error(404, `Unknown space "${id}".`);
    const b = (body ?? {}) as { display_name?: string; description?: string };
    const space: Space = {
        ...existing,
        displayName: b.display_name?.trim() || existing.id,
        description: b.description?.trim() ?? existing.description,
    };
    store.put(SERVER_SPACE, SPACES_COLL, id, space);
    return json(space);
}

/** DELETE /spaces/{id}?purge= — deregister + drop the space's mock data. */
function deleteSpace(store: MockStore, id: string, params: Record<string, string>) {
    if (id === 'default') return error(400, 'The default space cannot be removed in mock mode.');
    if (!store.has(SERVER_SPACE, SPACES_COLL, id)) return error(404, `Unknown space "${id}".`);
    store.delete(SERVER_SPACE, SPACES_COLL, id);
    store.clearSpace(id);
    return json({ id, deleted: true, purged: params['purge'] === 'true' });
}

/** The registry always carries the `default` space, so a fresh (or fully-emptied) server still boots. */
function ensureRegistry(store: MockStore): void {
    if (store.has(SERVER_SPACE, SPACES_COLL, 'default')) return;
    store.put<Space>(SERVER_SPACE, SPACES_COLL, 'default', {
        id: 'default',
        displayName: 'Default',
        description: 'The default demo space (CDR ingest sample).',
        createdAt: new Date().toISOString(), // first-seen time — stable afterwards (persisted)
    });
}
