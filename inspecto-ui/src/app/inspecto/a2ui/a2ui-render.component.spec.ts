import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { of } from 'rxjs';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { A2uiArtifact } from './a2ui-artifact';
import { A2uiRenderComponent } from './a2ui-render.component';
import { isNavigableTarget } from './route-validation';

/** Test route config: a lazy feature (like app.routes.ts entries) behind a componentless '' wrapper. */
const TEST_ROUTES = [
    {
        path: '',
        children: [
            { path: 'catalog', loadChildren: () => Promise.resolve([]) },
            { path: 'runs', loadChildren: () => Promise.resolve([]) },
        ],
    },
];

function create(artifact: A2uiArtifact) {
    TestBed.configureTestingModule({
        imports: [A2uiRenderComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter(TEST_ROUTES),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(A2uiRenderComponent);
    fixture.componentRef.setInput('artifact', artifact);
    return fixture;
}

describe('A2uiRenderComponent', () => {
    it('renders a text artifact as plain text (no HTML interpretation)', () => {
        const fixture = create({ kind: 'text', title: 'Summary', config: { text: 'line one\n<b>not html</b>' } });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('Summary');
        expect(el.textContent).toContain('<b>not html</b>'); // literal text, never markup
        expect(el.querySelector('b')).toBeNull();
    });

    it('renders a kpi artifact through <inspecto-kpi> with mapped value/label', () => {
        const fixture = create({ kind: 'kpi', config: { value: 99, label: 'Rows loaded' } });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('inspecto-kpi')).toBeTruthy();
        expect(el.textContent).toContain('Rows loaded');
        expect(el.textContent).toContain('99');
    });

    it('maps a chart artifact to Chart.js inputs (computed only — Chart.js cannot paint in jsdom)', () => {
        const data = { labels: ['a', 'b'], datasets: [{ data: [1, 2] }] };
        const c = create({ kind: 'chart', config: { type: 'line', data, options: { plugins: {} } } }).componentInstance;
        expect(c.chartType()).toBe('line');
        expect(c.chartData()).toEqual(data);
        expect(c.chartOptions()).toEqual({ plugins: {} });
    });

    it('renders a data-table artifact through <inspecto-data-table>, mapping string columns to ColDefs', () => {
        const fixture = create({
            kind: 'data-table',
            config: { rows: [{ a: 1, b: 2 }], columns: ['a', 'b'] },
        });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect((fixture.nativeElement as HTMLElement).querySelector('inspecto-data-table')).toBeTruthy();
        expect(c.tableRows()).toEqual([{ a: 1, b: 2 }]);
        expect(c.tableColumns()).toEqual([{ field: 'a' }, { field: 'b' }]);
    });

    it('fails closed: an unknown kind renders the empty-state placeholder, never raw content', () => {
        const fixture = create({ kind: 'iframe', config: { src: 'https://evil.example' } });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('inspecto-empty-state')).toBeTruthy();
        expect(el.textContent).toContain("This component type isn't supported.");
        expect(el.querySelector('iframe')).toBeNull();
    });

    it('degrades wrong-typed config to defaults instead of throwing', () => {
        const fixture = create({ kind: 'kpi', config: { value: 'not-a-number', label: 42 } as never });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.kpiValue()).toBe(0);
        expect(c.kpiLabel()).toBe('Value');
    });

    it('renders only navigate-intent actions; a bogus target is disabled and never navigates', () => {
        const fixture = create({
            kind: 'text',
            config: { text: 'go' },
            actions: [
                { label: 'Open catalog', intent: 'navigate', target: '/catalog/datasets' },
                { label: 'Bogus', intent: 'navigate', target: '/no-such-route' },
                { label: 'External', intent: 'navigate', target: 'https://evil.example' },
                { label: 'Materialize', intent: 'invoke', target: '/runs' }, // S6 — hidden this slice
            ],
        });
        fixture.detectChanges();
        const router = TestBed.inject(Router);
        const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

        const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
        expect(buttons.map((b) => b.textContent?.trim())).toEqual(['Open catalog', 'Bogus', 'External']);
        expect(buttons[0].disabled).toBe(false);
        expect(buttons[1].disabled).toBe(true);
        expect(buttons[2].disabled).toBe(true);

        buttons[1].click();
        buttons[2].click();
        expect(navigate).not.toHaveBeenCalled();
        buttons[0].click();
        expect(navigate).toHaveBeenCalledWith('/catalog/datasets');
    });

    it('renders nested parts recursively, capped at depth 3', () => {
        // 5 levels of nesting — only 3 nested hosts may materialize below the root.
        let leaf: A2uiArtifact = { kind: 'text', config: { text: 'deepest' } };
        for (let i = 0; i < 5; i++) leaf = { kind: 'text', config: { text: `level ${i}` }, parts: [leaf] };
        const fixture = create(leaf);
        fixture.detectChanges();
        const nested = (fixture.nativeElement as HTMLElement).querySelectorAll('inspecto-a2ui-render');
        expect(nested.length).toBe(3);
    });

    it('renders with no a11y violations', async () => {
        const fixture = create({
            kind: 'text',
            title: 'Answer',
            config: { text: 'All good.' },
            actions: [{ label: 'Open runs', intent: 'navigate', target: '/runs' }],
        });
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('isNavigableTarget', () => {
    const routes = TEST_ROUTES;

    it('accepts a configured lazy route and its deeper segments', () => {
        expect(isNavigableTarget(routes, '/catalog')).toBe(true);
        expect(isNavigableTarget(routes, '/catalog/datasets')).toBe(true);
    });

    it('rejects unknown routes, external URLs, and non-strings', () => {
        expect(isNavigableTarget(routes, '/nope')).toBe(false);
        expect(isNavigableTarget(routes, 'https://evil.example')).toBe(false);
        expect(isNavigableTarget(routes, '//evil.example/catalog')).toBe(false);
        expect(isNavigableTarget(routes, 'catalog')).toBe(false); // relative — must be in-app absolute
        expect(isNavigableTarget(routes, 42)).toBe(false);
        expect(isNavigableTarget(routes, '/')).toBe(false);
    });
});
