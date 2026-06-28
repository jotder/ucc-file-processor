import { describe, expect, it } from 'vitest';
import { getKind } from 'app/inspecto/component-model';
import { DASHBOARD_KIND, dashboardParts, validateDashboardConfig } from './dashboard.kind';
import { buildDashboard } from './dashboard-types';

describe('dashboard ComponentKind', () => {
    it('registers as a composite layout kind whose parts are charts', () => {
        expect(getKind('dashboard')).toBe(DASHBOARD_KIND);
        expect(DASHBOARD_KIND.wiring).toBe('layout');
        expect(DASHBOARD_KIND.allowedPartKinds).toEqual(['chart']);
    });

    it('derives layout tiles from the dashboard config', () => {
        const cfg = { tiles: [{ chartId: 'a', span: 2 as const }, { chartId: 'b', span: 1 as const }] };
        const wiring = DASHBOARD_KIND.deriveWiring!([], cfg);
        expect(wiring).toEqual({ strategy: 'layout', tiles: [{ partId: 'tile0', w: 2 }, { partId: 'tile1', w: 1 }] });
    });

    it('exposes one chart part per tile (composition for the reuse graph)', () => {
        const parts = dashboardParts({ tiles: [{ chartId: 'a', span: 1 }, { chartId: 'b', span: 1 }] });
        expect(parts).toEqual([
            { partId: 'tile0', ref: { kind: 'chart', id: 'a' } },
            { partId: 'tile1', ref: { kind: 'chart', id: 'b' } },
        ]);
    });

    it('flags a tile that references no chart', () => {
        const findings = validateDashboardConfig({ tiles: [{ chartId: '', span: 1 }] });
        expect(findings.some((f) => f.severity === 'error' && f.path === 'tiles')).toBe(true);
    });

    it('buildDashboard seeds an empty AND filter', () => {
        const d = buildDashboard('d1', [{ chartId: 'a', span: 1 }]);
        expect(d.filter).toEqual({ kind: 'group', op: 'AND', items: [] });
    });
});
