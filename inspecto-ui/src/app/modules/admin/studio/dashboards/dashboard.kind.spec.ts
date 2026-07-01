import { describe, expect, it } from 'vitest';
import { getKind } from 'app/inspecto/component-model';
import { DASHBOARD_KIND, dashboardParts, validateDashboardConfig } from './dashboard.kind';
import { buildDashboard } from './dashboard-types';

describe('dashboard ComponentKind', () => {
    it('registers as a composite layout kind whose parts are widgets', () => {
        expect(getKind('dashboard')).toBe(DASHBOARD_KIND);
        expect(DASHBOARD_KIND.wiring).toBe('layout');
        expect(DASHBOARD_KIND.allowedPartKinds).toEqual(['widget']);
    });

    it('derives layout tiles from the dashboard config', () => {
        const cfg = { tiles: [{ widgetId: 'a', span: 2 as const }, { widgetId: 'b', span: 1 as const }] };
        const wiring = DASHBOARD_KIND.deriveWiring!([], cfg);
        expect(wiring).toEqual({ strategy: 'layout', tiles: [{ partId: 'tile0', w: 2 }, { partId: 'tile1', w: 1 }] });
    });

    it('exposes one widget part per tile (composition for the reuse graph)', () => {
        const parts = dashboardParts({ tiles: [{ widgetId: 'a', span: 1 }, { widgetId: 'b', span: 1 }] });
        expect(parts).toEqual([
            { partId: 'tile0', ref: { kind: 'widget', id: 'a' } },
            { partId: 'tile1', ref: { kind: 'widget', id: 'b' } },
        ]);
    });

    it('flags a tile that references no widget', () => {
        const findings = validateDashboardConfig({ tiles: [{ widgetId: '', span: 1 }] });
        expect(findings.some((f) => f.severity === 'error' && f.path === 'tiles')).toBe(true);
    });

    it('buildDashboard seeds an empty AND filter', () => {
        const d = buildDashboard('d1', [{ widgetId: 'a', span: 1 }]);
        expect(d.filter).toEqual({ kind: 'group', op: 'AND', items: [] });
    });
});
