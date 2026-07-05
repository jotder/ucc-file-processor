import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoChartComponent } from './chart.component';

// Chart.js cannot paint in jsdom; the fixture keeps `data` null so rebuild() never instantiates a
// Chart — the a11y contract under test is the canvas wrapper (role="img" + alt text), not the pixels.
function create() {
    TestBed.configureTestingModule({
        imports: [InspectoChartComponent],
        providers: [provideNoopAnimations(), { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const fixture = TestBed.createComponent(InspectoChartComponent);
    fixture.componentRef.setInput('type', 'bar');
    fixture.detectChanges();
    return fixture;
}

describe('InspectoChartComponent', () => {
    it('exposes the canvas as role="img" with a data-derived text alternative', () => {
        const fixture = create();
        const canvas = (fixture.nativeElement as HTMLElement).querySelector('canvas')!;
        expect(canvas.getAttribute('role')).toBe('img');
        expect(canvas.getAttribute('aria-label')).toBe('bar chart');

        // Assign directly (not setInput) so ngOnChanges/rebuild — which needs a real canvas — is not run.
        fixture.componentInstance.data = { labels: ['a', 'b'], datasets: [{ data: [1, 2] }] };
        fixture.detectChanges();
        expect(canvas.getAttribute('aria-label')).toBe('bar chart. a: 1, b: 2.');
    });

    it('has no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
