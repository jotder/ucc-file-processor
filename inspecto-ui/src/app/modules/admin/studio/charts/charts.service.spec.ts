import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { ChartsService } from './charts.service';
import { buildChart } from './chart-types';

function setup() {
    const create = vi.fn((_t: string, c: Record<string, unknown>) =>
        of({ type: 'chart', name: String(c['id']), ref: `chart/${c['id']}`, content: c }),
    );
    const list = vi.fn(() =>
        of([{ type: 'chart', name: 'c1', ref: 'chart/c1', content: { name: 'c1', datasetId: 'cdr', vizType: 'bar', controls: {} } }]),
    );
    TestBed.configureTestingModule({
        providers: [ChartsService, { provide: ComponentsService, useValue: { create, list, remove: vi.fn(() => of(null)) } }],
    });
    return { svc: TestBed.inject(ChartsService), create, list };
}

describe('ChartsService', () => {
    it('saves a chart as a "chart" registry component', () => {
        const { svc, create } = setup();
        svc.save(buildChart('c1', 'cdr', 'bar', { x: [{ field: 'tariff' }] })).subscribe();
        expect(create).toHaveBeenCalledWith('chart', expect.objectContaining({ id: 'c1', datasetId: 'cdr', vizType: 'bar' }));
    });

    it('lists charts back from the registry', () => {
        const { svc } = setup();
        let charts: { name: string; vizType: string }[] = [];
        svc.list().subscribe((c) => (charts = c));
        expect(charts[0].name).toBe('c1');
        expect(charts[0].vizType).toBe('bar');
    });
});
