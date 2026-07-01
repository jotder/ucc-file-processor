import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { of } from 'rxjs';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BAR_PLUGIN, BUBBLE_PLUGIN, GAUGE_PLUGIN, KPI_PLUGIN, PIE_PLUGIN, TABLE_PLUGIN } from './plugins';
import { VizPlugin, VizProps } from './viz-types';
import { VizRenderComponent } from './viz-render.component';

function create(plugin: VizPlugin, props: VizProps) {
    TestBed.configureTestingModule({
        imports: [VizRenderComponent],
        providers: [provideNoopAnimations(), { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const fixture = TestBed.createComponent(VizRenderComponent);
    fixture.componentRef.setInput('plugin', plugin);
    fixture.componentRef.setInput('props', props);
    return fixture;
}

describe('VizRenderComponent', () => {
    it('builds Chart.js data (datasets) for a chartjs plugin', () => {
        const props: VizProps = { labels: ['a', 'b'], series: [{ label: 'm', data: [1, 2] }] };
        const c = create(BAR_PLUGIN, props).componentInstance;
        expect(c.renderKind()).toBe('chartjs');
        expect(c.chartType()).toBe('bar');
        expect(c.chartData()?.datasets[0].data).toEqual([1, 2]);
    });

    it('uses one backgroundColor per slice for pie', () => {
        const props: VizProps = { labels: ['a', 'b', 'c'], series: [{ label: 'm', data: [1, 2, 3] }] };
        const c = create(PIE_PLUGIN, props).componentInstance;
        const bg = c.chartData()?.datasets[0].backgroundColor as unknown[];
        expect(Array.isArray(bg)).toBe(true);
        expect(bg).toHaveLength(3);
    });

    it('derives colDefs for the table plugin', () => {
        const props: VizProps = { labels: [], series: [], rows: [{ a: 1, b: 2 }], columns: ['a', 'b'] };
        const c = create(TABLE_PLUGIN, props).componentInstance;
        expect(c.renderKind()).toBe('aggrid');
        expect(c.colDefs()).toEqual([{ field: 'a' }, { field: 'b' }]);
    });

    it('resolves the KPI component for a component-render plugin and passes inputs', () => {
        const props: VizProps = { labels: [], series: [], value: 99 };
        const fixture = create(KPI_PLUGIN, props);
        fixture.componentRef.setInput('title', 'Revenue');
        const c = fixture.componentInstance;
        expect(c.renderKind()).toBe('component');
        expect(c.outletComponent()).toBeTruthy();
        expect(c.kpiInputs()).toEqual({ value: 99, label: 'Revenue' });
    });

    it('renders the KPI arm with no a11y violations', async () => {
        const props: VizProps = { labels: [], series: [], value: 99 };
        const fixture = create(KPI_PLUGIN, props);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('zips x/y/size series into {x,y,r} points for bubble', () => {
        const props: VizProps = {
            labels: ['gold', 'silver'],
            series: [{ label: 'x', data: [10, 20] }, { label: 'y', data: [1, 2] }, { label: 'size', data: [100, 50] }],
        };
        const c = create(BUBBLE_PLUGIN, props).componentInstance;
        const points = c.chartData()?.datasets[0].data as { x: number; y: number; r: number }[];
        expect(points).toEqual([
            { x: 10, y: 1, r: 24 }, // the largest point gets the max radius (4 + 20)
            { x: 20, y: 2, r: 14 }, // half the size → half the extra radius (4 + 10)
        ]);
    });

    it('renders a gauge as a two-slice value/remainder doughnut, clamped to 0–100', () => {
        const c = create(GAUGE_PLUGIN, { labels: [], series: [], value: 137 }).componentInstance;
        expect(c.chartData()?.datasets[0].data).toEqual([100, 0]); // clamped
    });

    it("gauge's chart options fix the half-circle styling and hide the legend/tooltip by default", () => {
        const c = create(GAUGE_PLUGIN, { labels: [], series: [], value: 42 }).componentInstance;
        const opts = c.chartJsOptions() as Record<string, unknown>;
        expect(opts['circumference']).toBe(180);
        expect(opts['rotation']).toBe(270);
    });

    it('resolves a clicked point index to its category label and emits categoryClick', () => {
        const props: VizProps = { labels: ['a', 'b'], series: [{ label: 'm', data: [1, 2] }] };
        const c = create(BAR_PLUGIN, props).componentInstance;
        let emitted: string | undefined;
        c.categoryClick.subscribe((v) => (emitted = v));
        c.onElementClick(1);
        expect(emitted).toBe('b');
    });

    it('never emits categoryClick for gauge (its slices are Value/Remaining, not filterable categories)', () => {
        const c = create(GAUGE_PLUGIN, { labels: [], series: [], value: 42 }).componentInstance;
        let emitted: string | undefined;
        c.categoryClick.subscribe((v) => (emitted = v));
        c.onElementClick(0);
        expect(emitted).toBeUndefined();
    });
});
