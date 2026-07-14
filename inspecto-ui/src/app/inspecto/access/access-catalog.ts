import { GammaNavigationItem } from '@gamma/components/navigation';
import { defaultNavigation } from 'app/mock-api/common/navigation/data';
import { AccessGrant, AccessNode } from '../api/access.service';

/**
 * Access Catalog derivation + grant resolution (framework-free — design
 * `docs/superpower/lens-access-config-design.md` §4). The UI is the source of truth for what exists
 * on screen: the catalog tree is derived from the platform navigation (menu groups → panes) with the
 * **action nodes** below grafted in, then snapshotted to the backend on save. New nav items appear in
 * the catalog automatically; a new gateable functionality is one entry in {@link ACCESS_ACTION_NODES}.
 */

/**
 * The functionality (action) nodes, grafted under their owning nav node — the honest list: exactly
 * one node per Capability that really gates something today, never one per pane sharing a capability
 * (denying it on one pane while the same capability drives three would lie).
 */
export const ACCESS_ACTION_NODES: Record<string, AccessNode[]> = {
    'workbench-group': [{
        id: 'workbench.author', kind: 'action', capability: 'canAuthorWorkbench',
        label: 'Author Workbench content (create / edit / delete)',
    }],
    runs: [{
        id: 'runs.operate', kind: 'action', capability: 'canOperateRuns',
        label: 'Operate runs (trigger / pause / resume / reprocess)',
    }],
    requirements: [{
        id: 'requirements.triage', kind: 'action', capability: 'canTriageRequirements',
        label: 'Triage requirements (accept / reject / deliver)',
    }],
    alerts: [{
        id: 'alerts.author', kind: 'action', capability: 'canAuthorAlertRules',
        label: 'Author alert rules',
    }],
    settings: [{
        id: 'access.configure', kind: 'action', capability: 'canConfigureAccess',
        label: 'Configure lens access',
    }],
};

/**
 * Map a navigation tree into catalog nodes: `collapsable` → `menu`, `basic` → `pane`, dividers
 * skipped. Menu-Builder custom menus never pass through here (they're per-Space curation, not
 * platform surface — the callers pass the static platform nav).
 */
export function deriveAccessCatalog(nav: GammaNavigationItem[]): AccessNode[] {
    const nodes: AccessNode[] = [];
    for (const item of nav) {
        if (item.type === 'divider' || !item.id || !item.title) continue;
        const node: AccessNode = {
            id: item.id,
            label: item.title,
            kind: item.type === 'collapsable' ? 'menu' : 'pane',
        };
        if (item.icon) node.icon = item.icon;
        if (item.link) node.link = item.link;
        const children = [
            ...(item.children?.length ? deriveAccessCatalog(item.children) : []),
            ...(ACCESS_ACTION_NODES[item.id] ?? []),
        ];
        if (children.length) node.children = children;
        nodes.push(node);
    }
    return nodes;
}

/** The catalog over the platform navigation (`mock-api/common/navigation/data.ts` — the canonical
 *  nav config, despite its historical location). */
export function deriveDefaultAccessCatalog(): AccessNode[] {
    return deriveAccessCatalog(defaultNavigation);
}

export interface CatalogIndex {
    byId: Map<string, AccessNode>;
    parentOf: Map<string, string | null>;
}

export function indexCatalog(nodes: AccessNode[]): CatalogIndex {
    const byId = new Map<string, AccessNode>();
    const parentOf = new Map<string, string | null>();
    const walk = (ns: AccessNode[], parent: string | null): void => {
        for (const n of ns) {
            byId.set(n.id, n);
            parentOf.set(n.id, parent);
            if (n.children?.length) walk(n.children, n.id);
        }
    };
    walk(nodes, null);
    return { byId, parentOf };
}

/** A node's resolved grant: what applies (`effective`), what is set on the node itself (`explicit`,
 *  null = inheriting), and where the applied value comes from (null = the allow root default). */
export interface GrantState {
    effective: AccessGrant;
    explicit: AccessGrant | null;
    sourceId: string | null;
    sourceLabel: string | null;
}

/** Walk self → root; the first explicit grant wins; no explicit ancestor = allow (today's behavior). */
export function resolveGrant(
    nodeId: string, grants: Record<string, AccessGrant>, idx: CatalogIndex): GrantState {
    const explicit = grants[nodeId] ?? null;
    let cursor: string | null = nodeId;
    while (cursor !== null) {
        const g = grants[cursor];
        if (g) {
            return { effective: g, explicit, sourceId: cursor, sourceLabel: idx.byId.get(cursor)?.label ?? cursor };
        }
        cursor = idx.parentOf.get(cursor) ?? null;
    }
    return { effective: 'allow', explicit: null, sourceId: null, sourceLabel: null };
}

/**
 * Drop navigation items (with their subtree) whose effective grant is deny. Items unknown to the
 * catalog — dividers, Menu-Builder custom menus — always stay: unknown = allow, so an empty or
 * missing profile leaves the sidebar byte-identical.
 */
export function filterNavByAccess(
    items: GammaNavigationItem[], grants: Record<string, AccessGrant>, idx: CatalogIndex): GammaNavigationItem[] {
    if (!Object.keys(grants).length) return items;
    const keep = (item: GammaNavigationItem): GammaNavigationItem | null => {
        if (item.id && idx.byId.has(item.id) && resolveGrant(item.id, grants, idx).effective === 'deny') {
            return null;
        }
        if (!item.children?.length) return item;
        return { ...item, children: item.children.map(keep).filter((c): c is GammaNavigationItem => c !== null) };
    };
    return items.map(keep).filter((i): i is GammaNavigationItem => i !== null);
}
