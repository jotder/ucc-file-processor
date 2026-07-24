import { error, json, match, MockHandler } from '../mock-http';
import { MockFlags } from '../mock-flags';
import { MockStore } from '../mock-store';

/**
 * Offline mock for the access configuration ({@code /access/catalog} + {@code /access/profiles*} +
 * {@code /access/roles} — {@code AccessRoutes}). Mirrors the backend's shape gates (422 on bad
 * subjectType / id mismatch / bad grant values / unknown capability) and its contracts: an empty
 * catalog is a state, and the roles GET is the authored overlay merged per-name onto the seed with
 * each row marked {@code source: authored|seed} (RBAC R1). Gated on {@code mockAccess}. Must be
 * registered ahead of {@code demoHandler} — its {@code /\/catalog$/} regex also matches
 * {@code /access/catalog} (the db-browser lesson).
 */

const CATALOG = /\/access\/catalog$/;
const PROFILES = /\/access\/profiles$/;
const PROFILE = /\/access\/profiles\/([^/?]+)$/;
const ROLES = /\/access\/roles$/;
const POLICIES = /\/access\/policies$/;
const EXPLAIN = /\/access\/explain(\?|$)/;

export const ACCESS_CATALOG_COLL = 'access-catalog';
export const ACCESS_PROFILE_COLL = 'access-profiles';
export const ACCESS_ROLES_COLL = 'access-roles';

const SUBJECT_TYPES = new Set(['lens', 'role']);
const GRANT_VALUES = new Set(['allow', 'deny']);

/** The route-gate capability vocabulary (backend `Roles.KNOWN_CAPABILITIES`, R4 manifest-derived). */
const KNOWN_CAPABILITIES = new Set([
    'canAuthorWorkbench', 'canOperateRuns', 'canTriageRequirements', 'canOnboardConnections',
    'canConfigureAccess', 'canAuthorAlertRules', 'canOfferDatasets', 'canRequestShares',
    'canApproveShares',
]);

/** The shipped seed defaults (mirrors backend `Roles.SEED`, corrected 2026-07-23). */
const BUILDER = ['canAuthorWorkbench', 'canAuthorAlertRules', 'canOfferDatasets', 'canRequestShares'];
const OPS = ['canOperateRuns', 'canRequestShares'];
const SEED_ROLES: { name: string; capabilities: string[] }[] = [
    { name: 'pipeline-developer', capabilities: BUILDER },
    { name: 'app-developer', capabilities: BUILDER },
    { name: 'developer', capabilities: BUILDER },
    { name: 'operations', capabilities: OPS },
    { name: 'support', capabilities: OPS },
    { name: 'admin', capabilities: ['canOnboardConnections', 'canConfigureAccess', 'canApproveShares', 'canTriageRequirements'] },
    { name: 'power', capabilities: ['canAuthorWorkbench', 'canAuthorAlertRules', 'canOperateRuns', 'canOfferDatasets', 'canRequestShares', 'canTriageRequirements'] },
    { name: 'super', capabilities: [...KNOWN_CAPABILITIES] },
    { name: 'business', capabilities: ['canTriageRequirements'] },
];

interface RoleRow {
    name: string;
    capabilities: string[];
    dataScopes?: string[];
}

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

        if (method === 'GET' && ROLES.test(url)) {
            return json(effectiveRoles(authoredRoles(store, space)));
        }
        if (method === 'PUT' && ROLES.test(url)) {
            const b = req.body as { roles?: unknown } | null;
            if (!Array.isArray(b?.roles)) return error(422, "role settings require a 'roles' list");
            const seen = new Set<string>();
            const authored: RoleRow[] = [];
            for (const raw of b.roles as Partial<RoleRow>[]) {
                const name = String(raw?.name ?? '').trim().toLowerCase();
                if (!name) return error(422, 'every role must be an object {name, capabilities, dataScopes?}');
                if (seen.has(name)) return error(422, `duplicate role '${name}'`);
                seen.add(name);
                const caps = Array.isArray(raw.capabilities) ? raw.capabilities.map(String) : [];
                for (const c of caps) {
                    if (!KNOWN_CAPABILITIES.has(c)) return error(422, `role '${name}': unknown capability '${c}'`);
                }
                const scopes = Array.isArray(raw.dataScopes)
                    ? raw.dataScopes.map((s) => String(s).trim().toLowerCase()).filter(Boolean)
                    : undefined;
                authored.push({ name, capabilities: caps, ...(scopes?.length ? { dataScopes: scopes } : {}) });
            }
            store.put(space, ACCESS_ROLES_COLL, 'authored', { id: 'authored', roles: authored });
            return json(effectiveRoles(authored));
        }
        // Access Policies (ABAC A2/A3): the offline mock represents the default, non-Enterprise edition
        // — the policy engine isn't bundled, so there are no seed policies and explain is disabled
        // (exactly what the real backend returns without inspecto-policy on the classpath).
        if (method === 'GET' && POLICIES.test(url)) {
            return json({ policies: [] });
        }
        if (method === 'GET' && EXPLAIN.test(url)) {
            return json({ enabled: false, reason: 'no access policy engine on this edition' });
        }
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

function authoredRoles(store: MockStore, space: string): RoleRow[] {
    const doc = store.get<{ roles?: RoleRow[] }>(space, ACCESS_ROLES_COLL, 'authored');
    return doc?.roles ?? [];
}

/** The backend's GET shape: authored rows overlaid on the seed PER NAME, each marked `source`. */
function effectiveRoles(authored: RoleRow[]): { roles: (RoleRow & { source: string })[] } {
    const byName = new Map(authored.map((r) => [r.name, r]));
    const rows: (RoleRow & { source: string })[] = [];
    for (const seed of SEED_ROLES) {
        const a = byName.get(seed.name);
        rows.push(a ? { ...a, source: 'authored' } : { ...seed, source: 'seed' });
        byName.delete(seed.name);
    }
    for (const a of byName.values()) rows.push({ ...a, source: 'authored' });
    return { roles: rows };
}
