import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { WidgetsService } from './widgets.service';
import { buildWidget } from './widget-types';

function setup() {
    const create = vi.fn((_t: string, c: Record<string, unknown>) =>
        of({ type: 'widget', name: String(c['id']), ref: `widget/${c['id']}`, content: c }),
    );
    const list = vi.fn(() =>
        of([{ type: 'widget', name: 'c1', ref: 'widget/c1', content: { name: 'c1', datasetId: 'cdr', vizType: 'bar', controls: {} } }]),
    );
    TestBed.configureTestingModule({
        providers: [WidgetsService, { provide: ComponentsService, useValue: { create, list, remove: vi.fn(() => of(null)) } }],
    });
    return { svc: TestBed.inject(WidgetsService), create, list };
}

describe('WidgetsService', () => {
    it('saves a widget as a "widget" registry component', () => {
        const { svc, create } = setup();
        svc.save(buildWidget('c1', 'cdr', 'bar', { x: [{ field: 'tariff' }] })).subscribe();
        expect(create).toHaveBeenCalledWith('widget', expect.objectContaining({ id: 'c1', datasetId: 'cdr', vizType: 'bar' }));
    });

    it('lists widgets back from the registry', () => {
        const { svc } = setup();
        let widgets: { name: string; vizType: string }[] = [];
        svc.list().subscribe((w) => (widgets = w));
        expect(widgets[0].name).toBe('c1');
        expect(widgets[0].vizType).toBe('bar');
    });

    it('round-trips a view-bound widget’s viewId through the content codec', () => {
        const stored = { name: 'map1', datasetId: '', vizType: 'geo-map', controls: {}, viewId: 'dhaka-network' };
        const create = vi.fn((_t: string, c: Record<string, unknown>) =>
            of({ type: 'widget', name: String(c['id']), ref: `widget/${c['id']}`, content: c }),
        );
        const list = vi.fn(() => of([{ type: 'widget', name: 'map1', ref: 'widget/map1', content: stored }]));
        TestBed.configureTestingModule({
            providers: [WidgetsService, { provide: ComponentsService, useValue: { create, list } }],
        });
        const svc = TestBed.inject(WidgetsService);
        svc.save(buildWidget('map1', '', 'geo-map', {}, { viewId: 'dhaka-network' })).subscribe();
        expect(create).toHaveBeenCalledWith('widget', expect.objectContaining({ viewId: 'dhaka-network' }));
        let widgets: { viewId?: string }[] = [];
        svc.list().subscribe((w) => (widgets = w));
        expect(widgets[0].viewId).toBe('dhaka-network');
    });
});
