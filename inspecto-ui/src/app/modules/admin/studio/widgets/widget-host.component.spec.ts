import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Widget } from './widget-types';
import { WidgetsService } from './widgets.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { WidgetHostComponent } from './widget-host.component';

const DS: Dataset = {
    id: 'cdr_sample',
    name: 'cdr_sample',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [{ name: 'duration_s', type: 'number', role: 'measure' }],
    measures: [],
};
const WIDGET: Widget = {
    id: 'total_dur',
    name: 'Total duration',
    datasetId: 'cdr_sample',
    vizType: 'kpi',
    controls: { value: [{ field: 'duration_s', agg: 'sum' }] },
};

function create(providers: unknown[] = []) {
    TestBed.configureTestingModule({
        imports: [WidgetHostComponent],
        providers: [provideNoopAnimations(), { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }, ...providers],
    });
    return TestBed.createComponent(WidgetHostComponent);
}

describe('WidgetHostComponent', () => {
    // No plugin side-effect import here, so seed the (guarded) builtins — order-independent under the
    // shared per-worker registry.
    beforeEach(() => registerBuiltinViz());

    it('pre-loaded mode: resolves the plugin from an already-supplied widget/dataset, no fetch', () => {
        const fixture = create([{ provide: WidgetsService, useValue: {} }, { provide: DatasetsService, useValue: {} }]);
        fixture.componentRef.setInput('widget', WIDGET);
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        expect(fixture.componentInstance.resolvedWidget()).toBe(WIDGET);
        expect(fixture.componentInstance.plugin()?.meta.type).toBe('kpi');
    });

    it('self-fetch mode: fetches the widget by id, then its dataset', () => {
        const fixture = create([
            { provide: WidgetsService, useValue: { get: () => of(WIDGET) } },
            { provide: DatasetsService, useValue: { get: () => of(DS) } },
        ]);
        fixture.componentRef.setInput('widgetId', 'total_dur');
        fixture.detectChanges();
        expect(fixture.componentInstance.resolvedWidget()).toEqual(WIDGET);
        expect(fixture.componentInstance.resolvedDataset()).toEqual(DS);
    });

    it('renders the empty (loading) state with no a11y violations', async () => {
        const fixture = create([{ provide: WidgetsService, useValue: {} }, { provide: DatasetsService, useValue: {} }]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('canExport is true for a chartjs-rendered widget, false for KPI (its component escape hatch)', () => {
        const fixture = create([{ provide: WidgetsService, useValue: {} }, { provide: DatasetsService, useValue: {} }]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', DS);
        expect(fixture.componentInstance.canExport()).toBe(true);
        fixture.componentRef.setInput('widget', WIDGET); // vizType: 'kpi'
        expect(fixture.componentInstance.canExport()).toBe(false);
    });

    it('resolves a category click to the widget’s x-channel field and emits a drill event', () => {
        const barWidget: Widget = { ...WIDGET, vizType: 'bar', controls: { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' }] } };
        const fixture = create([{ provide: WidgetsService, useValue: {} }, { provide: DatasetsService, useValue: {} }]);
        fixture.componentRef.setInput('widget', barWidget);
        fixture.componentRef.setInput('dataset', DS);
        let emitted: { field: string; value: string } | undefined;
        fixture.componentInstance.drill.subscribe((v) => (emitted = v));
        fixture.componentInstance.onCategoryClick('premium');
        expect(emitted).toEqual({ field: 'tariff', value: 'premium' });
    });
});
