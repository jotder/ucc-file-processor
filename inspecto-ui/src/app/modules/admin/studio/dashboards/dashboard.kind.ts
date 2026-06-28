import { ComponentKind, ConfigFinding, Part, Wiring, getKind, registerKind } from 'app/inspecto/component-model';
import { DashboardConfig } from './dashboard-types';

/**
 * The `dashboard` {@link ComponentKind} — a **composite** kind: its parts are `chart` references, wired as a
 * `layout` (the grid). `deriveWiring` turns the ordered tiles into layout tiles. The first kind to use the
 * `layout` wiring variant (deferred in P0 until a real consumer existed — now this one). Authoring = the grid
 * editor; no exec (it composes already-executable charts).
 */
export const DASHBOARD_KIND: ComponentKind<DashboardConfig> = {
    id: 'dashboard',
    label: 'Dashboard',
    allowedPartKinds: ['chart'],
    wiring: 'layout',
    config: {
        validate: validateDashboardConfig,
        create: () => ({ tiles: [], filter: null }),
    },
    deriveWiring: (_parts: Part[], config: DashboardConfig): Wiring => ({
        strategy: 'layout',
        tiles: config.tiles.map((t, i) => ({ partId: `tile${i}`, w: t.span })),
    }),
    authoring: { editorKey: 'dashboard' },
};

/** The dashboard's parts: one chart reference per tile (the composition the reuse-graph reads). */
export function dashboardParts(config: DashboardConfig): Part[] {
    return config.tiles.map((t, i) => ({ partId: `tile${i}`, ref: { kind: 'chart', id: t.chartId } }));
}

/** Tiny hand-written validator: a dashboard needs at least one tile, and every tile names a chart. */
export function validateDashboardConfig(config: unknown): ConfigFinding[] {
    const c = (config ?? {}) as Partial<DashboardConfig>;
    const findings: ConfigFinding[] = [];
    if (!c.tiles?.length) {
        findings.push({ severity: 'warning', path: 'tiles', message: 'Add at least one chart.' });
    }
    if (c.tiles?.some((t) => !t.chartId)) {
        findings.push({ severity: 'error', path: 'tiles', message: 'Every tile must reference a chart.' });
    }
    return findings;
}

if (!getKind(DASHBOARD_KIND.id)) {
    registerKind(DASHBOARD_KIND);
}
