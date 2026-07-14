import { error, json, match, MockHandler } from '../mock-http';
import { MockFlags } from '../mock-flags';

/**
 * Offline mock for the lens access configuration ({@code /access/catalog} + {@code /access/profiles*}
 * — {@code AccessRoutes}). Mirrors the backend's shape gates (422 on bad subjectType / id mismatch /
 * bad grant values) and its empty-catalog-is-a-state contract. Gated on {@code mockAccess}. Must be
 * registered ahead of {@code demoHandler} — its {@code /\/catalog$/} regex also matches
 * {@code /access/catalog} (the db-browser lesson).
 */

const CATALOG = /\/access\/catalog$/;
const PROFILES = /\/access\/profiles$/;
const PROFILE = /\/access\/profiles\/([^/?]+)$/;

export const ACCESS_CATALOG_COLL = 'access-catalog';
export const ACCESS_PROFILE_COLL = 'access-profiles';

const SUBJECT_TYPES = new Set(['lens', 'role']);
const GRANT_VALUES = new Set(['allow', 'deny']);

interface ProfileDoc {
    id: string;
    subjectType: string;
    subjectId: string;
    label: string;
    grants: Record<string, string>;
}

export function accessHandler(flags: MockFlags): MockHandler {
    return (req, store) => {
        if (!flags.mockAccess) return undefined;
        const { method, url, space } = req;

        if (method === 'GET' && CATALOG.test(url)) {
            return json(store.get(space, ACCESS_CATALOG_COLL, 'catalog')
                ?? { name: 'catalog', version: 0, nodes: [] });
        }
        if (method === 'PUT' && CATALOG.test(url)) {
            const b = req.body as { version?: number; nodes?: unknown } | null;
            if (!Array.isArray(b?.nodes)) return error(422, 'access catalog requires a \'nodes\' list');
            const doc = { name: 'catalog', version: b.version ?? 1, nodes: b.nodes };
            return json(store.put(space, ACCESS_CATALOG_COLL, 'catalog', doc));
        }
        if (method === 'GET' && PROFILES.test(url)) {
            return json(store.list<ProfileDoc>(space, ACCESS_PROFILE_COLL)
                .sort((a, b) => a.id.localeCompare(b.id)));
        }

        const m = match(url, PROFILE);
        if (m && method === 'PUT') {
            const id = m[1];
            const b = (req.body ?? {}) as Partial<ProfileDoc>;
            if (!SUBJECT_TYPES.has(String(b.subjectType))) {
                return error(422, 'access profile requires subjectType lens|role');
            }
            if (!b.subjectId || id !== `${b.subjectType}-${b.subjectId}`) {
                return error(422, `access profile id '${id}' must equal '<subjectType>-<subjectId>'`);
            }
            const grants: Record<string, string> = {};
            for (const [nodeId, value] of Object.entries(b.grants ?? {})) {
                if (!GRANT_VALUES.has(String(value))) {
                    return error(422, `grant '${nodeId}' has value '${value}' (expected allow|deny)`);
                }
                grants[nodeId] = String(value);
            }
            const doc: ProfileDoc = {
                id,
                subjectType: String(b.subjectType),
                subjectId: String(b.subjectId),
                label: b.label?.trim() || String(b.subjectId),
                grants,
            };
            return json(store.put(space, ACCESS_PROFILE_COLL, id, doc));
        }
        if (m && method === 'DELETE') {
            const id = m[1];
            if (!store.get(space, ACCESS_PROFILE_COLL, id)) {
                return error(404, `access profile '${id}' not found`);
            }
            store.delete(space, ACCESS_PROFILE_COLL, id);
            return json({ deleted: id });
        }
        return undefined;
    };
}
