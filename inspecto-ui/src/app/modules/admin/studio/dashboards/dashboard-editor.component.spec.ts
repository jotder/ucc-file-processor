import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Chart } from '../charts/chart-types';
import { ChartsService } from '../charts/charts.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Dashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';
import { DashboardEditorComponent } from './dashboard-editor.component';

const DS: Dataset = {
    id: 'cdr_sample', name: 'cdr_sample', kind: 'virtual', sourceName: 'cdr',
    columns: [{ name: 'tariff', type: 'string', role: 'dimension' }, { name: 'duration_s', type: 'number', role: 'measure' }],
    measures: [],
};
const CHART: Chart = { id: 'bar1', name: 'Bar 1', datasetId: 'cdr_sample', vizType: 'bar', controls: { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' }] } };

function create(save = vi.fn((d: Dashboard) => of(d))) {
    TestBed.configureTestingModule({
        imports: [DashboardEditorComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ChartsService, useValue: { list: () => of([CHART]) } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: DashboardsService, useValue: { get: () => of(null), save } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    return TestBed.createComponent(DashboardEditorComponent);
}

describe('DashboardEditorComponent', () => {
    it('adds, spans and removes tiles', () => {
        const c = create().componentInstance;
        c.addChart('bar1');
        expect(c.tiles()).toEqual([{ chartId: 'bar1', span: 1 }]);
        c.toggleSpan(0);
        expect(c.tiles()[0].span).toBe(2);
        c.removeTile(0);
        expect(c.tiles()).toHaveLength(0);
    });

    it('builds the cross-filter column union from the tiled charts’ datasets', () => {
        const fixture = create();
        fixture.detectChanges(); // load charts + datasets
        const c = fixture.componentInstance;
        c.addChart('bar1');
        expect(c.filterColumns().map((col) => col.name)).toEqual(['tariff', 'duration_s']);
    });

    it('saves a dashboard with its tiles and navigates back', () => {
        const save = vi.fn((d: Dashboard) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.form.controls.name.setValue('cdr_overview');
        fixture.componentInstance.addChart('bar1');
        fixture.componentInstance.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ id: 'cdr_overview', tiles: [{ chartId: 'bar1', span: 1 }] }));
        expect(nav).toHaveBeenCalledWith(['/studio/dashboards']);
    });

    it('does not save with no tiles', () => {
        const save = vi.fn((d: Dashboard) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        fixture.componentInstance.form.controls.name.setValue('empty');
        fixture.componentInstance.save();
        expect(save).not.toHaveBeenCalled();
    });

    it('renders the empty editor with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
