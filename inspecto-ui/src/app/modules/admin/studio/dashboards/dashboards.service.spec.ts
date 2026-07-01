import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { DashboardsService } from './dashboards.service';
import { buildDashboard } from './dashboard-types';

function setup() {
    const create = vi.fn((_t: string, c: Record<string, unknown>) =>
        of({ type: 'dashboard', name: String(c['id']), ref: `dashboard/${c['id']}`, content: c }),
    );
    const list = vi.fn(() =>
        of([{ type: 'dashboard', name: 'd1', ref: 'dashboard/d1', content: { name: 'd1', tiles: [{ widgetId: 'c1', span: 1 }] } }]),
    );
    TestBed.configureTestingModule({
        providers: [DashboardsService, { provide: ComponentsService, useValue: { create, list, remove: vi.fn(() => of(null)) } }],
    });
    return { svc: TestBed.inject(DashboardsService), create, list };
}

describe('DashboardsService', () => {
    it('saves a dashboard as a "dashboard" registry component', () => {
        const { svc, create } = setup();
        svc.save(buildDashboard('d1', [{ widgetId: 'c1', span: 1 }])).subscribe();
        expect(create).toHaveBeenCalledWith('dashboard', expect.objectContaining({ id: 'd1' }));
    });

    it('lists dashboards back with their tiles', () => {
        const { svc } = setup();
        let dashboards: { name: string; tiles: unknown[] }[] = [];
        svc.list().subscribe((d) => (dashboards = d));
        expect(dashboards[0].name).toBe('d1');
        expect(dashboards[0].tiles).toHaveLength(1);
    });
});
