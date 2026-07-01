import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Widget } from '../widgets/widget-types';
import { WidgetsService } from '../widgets/widgets.service';
import { WidgetHostComponent } from '../widgets/widget-host.component';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { DashboardTileComponent } from './dashboard-tile.component';

const DS: Dataset = {
    id: 'cdr_sample',
    name: 'cdr_sample',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [{ name: 'duration_s', type: 'number', role: 'measure' }],
    measures: [],
};
const WIDGET: Widget = { id: 'total_dur', name: 'Total duration', datasetId: 'cdr_sample', vizType: 'kpi', controls: { value: [{ field: 'duration_s', agg: 'sum' }] } };

function create() {
    TestBed.configureTestingModule({
        imports: [DashboardTileComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            // Pre-loaded mode never calls these (widget/dataset are already supplied), but WidgetHostComponent
            // still injects them unconditionally in its constructor.
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(DashboardTileComponent);
    fixture.componentRef.setInput('widget', WIDGET);
    fixture.componentRef.setInput('dataset', DS);
    fixture.componentRef.setInput('filter', null);
    fixture.detectChanges();
    return fixture;
}

describe('DashboardTileComponent', () => {
    // The tile imports only the viz barrel (no plugin side-effect), so seed the (guarded) builtins
    // — order-independent under the shared per-worker registry.
    beforeEach(() => registerBuiltinViz());

    it('passes its widget/dataset through to the shared WidgetHostComponent, which resolves the plugin', () => {
        const fixture = create();
        const host = fixture.debugElement.query(By.directive(WidgetHostComponent)).componentInstance as WidgetHostComponent;
        expect(host.plugin()?.meta.type).toBe('kpi');
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create().nativeElement);
    });
});
