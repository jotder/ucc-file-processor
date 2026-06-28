import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Chart } from '../charts/chart-types';
import { Dataset } from '../datasets/dataset-types';
import { DashboardTileComponent } from './dashboard-tile.component';

const DS: Dataset = {
    id: 'cdr_sample',
    name: 'cdr_sample',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [{ name: 'duration_s', type: 'number', role: 'metric' }],
    metrics: [],
};
const CHART: Chart = { id: 'total_dur', name: 'Total duration', datasetId: 'cdr_sample', vizType: 'kpi', controls: { value: [{ field: 'duration_s', agg: 'sum' }] } };

function create() {
    TestBed.configureTestingModule({
        imports: [DashboardTileComponent],
        providers: [provideNoopAnimations(), { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const fixture = TestBed.createComponent(DashboardTileComponent);
    fixture.componentRef.setInput('chart', CHART);
    fixture.componentRef.setInput('dataset', DS);
    fixture.componentRef.setInput('filter', null);
    fixture.detectChanges();
    return fixture;
}

describe('DashboardTileComponent', () => {
    // The tile imports only the viz barrel (no plugin side-effect), so seed the (guarded) builtins
    // — order-independent under the shared per-worker registry.
    beforeEach(() => registerBuiltinViz());

    it('resolves the chart’s viz plugin', () => {
        expect(create().componentInstance.plugin()?.meta.type).toBe('kpi');
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create().nativeElement);
    });
});
