import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { of } from 'rxjs';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BAR_PLUGIN, KPI_PLUGIN, PIE_PLUGIN, TABLE_PLUGIN } from './plugins';
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
});
