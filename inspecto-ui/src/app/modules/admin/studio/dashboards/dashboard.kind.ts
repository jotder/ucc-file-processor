import { ComponentKind, ConfigFinding, Part, Ref, Wiring, dashboardRefs, getKind, hasEditorRoute, registerEditorRoute, registerKind } from 'app/inspecto/component-model';
import { DashboardConfig } from './dashboard-types';

/**
 * The `dashboard` {@link ComponentKind} — a **composite** kind: its parts are `widget` references, wired as a
 * `layout` (the grid). `deriveWiring` turns the ordered tiles into layout tiles. The first kind to use the
 * `layout` wiring variant (deferred in P0 until a real consumer existed — now this one). Authoring = the grid
 * editor; no exec (it composes already-executable widgets).
 */
export const DASHBOARD_KIND: ComponentKind<DashboardConfig> = {
    id: 'dashboard',
    label: 'Dashboard',
    allowedPartKinds: ['widget'],
    wiring: 'layout',
    config: {
        validate: validateDashboardConfig,
        create: () => ({ tiles: [], filter: null }),
    },
    deriveWiring: (_parts: Part[], config: DashboardConfig): Wiring => ({
        strategy: 'layout',
        tiles: config.tiles.map((t, i) => ({ partId: `tile${i}`, w: t.span })),
    }),
    deriveRefs: (config: DashboardConfig): Ref[] => dashboardRefs(config as unknown as Record<string, unknown>),
    authoring: { editorKey: 'dashboard' },
};

/** The dashboard's parts: one widget reference per tile — the R1 ref derivation, lifted to Parts. */
export function dashboardParts(config: DashboardConfig): Part[] {
    return dashboardRefs(config as unknown as Record<string, unknown>).map((r) => ({
        partId: r.via ?? r.id,
        ref: { kind: r.kind, id: r.id },
    }));
}

/** Tiny hand-written validator: a dashboard needs at least one tile, and every tile names a widget. */
export function validateDashboardConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<DashboardConfig>;
    const findings: ConfigFinding[] = [];
    if (!c.tiles?.length) {
        findings.push({ severity: 'warning', path: 'tiles', message: 'Add at least one widget.' });
    }
    if (c.tiles?.some((t) => !t.widgetId)) {
        findings.push({ severity: 'error', path: 'tiles', message: 'Every tile must reference a widget.' });
    }
    return findings;
}

if (!getKind(DASHBOARD_KIND.id)) {
    registerKind(DASHBOARD_KIND);
}
if (!hasEditorRoute('dashboard')) {
    registerEditorRoute('dashboard', (id) => ['/studio/dashboards', id]);
}
