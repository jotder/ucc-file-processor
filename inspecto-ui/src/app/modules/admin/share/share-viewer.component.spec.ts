import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { of, throwError } from 'rxjs';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { GammaConfigService } from '@gamma/services/config';
import { ShareService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ShareViewerComponent, embedQueryBody } from './share-viewer.component';

/** A widget mapping x→region, y→sum(amount) on the bar plugin — the canonical embeddable shape. */
const BAR_WIDGET = {
    name: 'Sales by region',
    datasetId: 'sales_ds',
    vizType: 'bar',
    controls: {
        x: [{ field: 'region' }],
        y: [{ field: 'amount', agg: 'sum' as const }],
    },
};

describe('embedQueryBody', () => {
    it('maps the plugin query back to validated agg/field pairs', () => {
        const body = embedQueryBody(BAR_WIDGET)!;
        expect(body.dataset).toBe('sales_ds');
        expect(body.groupBy).toEqual(['region']);
        expect(body.measures).toEqual([{ agg: 'sum', field: 'amount' }]);
        expect(body.limit).toBeGreaterThan(0);
    });

    it('declares view-bound and expression-measure widgets not embeddable', () => {
        expect(embedQueryBody({ ...BAR_WIDGET, viewId: 'geo-1' })).toBeNull();
        expect(embedQueryBody({
            ...BAR_WIDGET,
            controls: { x: [{ field: 'region' }], y: [{ field: 'm', expression: 'sum(a)/sum(b)' }] },
        })).toBeNull();
        expect(embedQueryBody({ ...BAR_WIDGET, vizType: 'no-such-viz' })).toBeNull();
    });
});

describe('ShareViewerComponent', () => {
    async function create(share: Partial<ShareService>) {
        TestBed.configureTestingModule({
            imports: [ShareViewerComponent],
            providers: [
                provideRouter([]),
                provideNoopAnimations(),
                // the chart render host injects the scheme-aware config service (viz-render house pattern)
                { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
                { provide: ShareService, useValue: share },
            ],
        });
        const fixture = TestBed.createComponent(ShareViewerComponent);
        fixture.componentRef.setInput('token', 't-123');
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        return fixture;
    }

    it('renders the invalid-link error state (and passes axe)', async () => {
        const fixture = await create({
            resolve: () => throwError(() => ({ status: 404, error: { error: 'not found' } })),
        } as Partial<ShareService>);
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        expect(el.textContent).toContain('Link unavailable');
        await expectNoA11yViolations(el);
    });

    it('renders tiles read-only: unsupported widgets degrade to an inline notice (and passes axe)', async () => {
        const fixture = await create({
            resolve: () => of({
                dashboard: { id: 'ops-dash', content: {
                    name: 'Ops Dashboard',
                    tiles: [{ widgetId: 'w-view', span: 2 }, { widgetId: 'w-gone', span: 1 }],
                } },
                widgets: [{ id: 'w-view', content: { ...BAR_WIDGET, viewId: 'geo-1' } }],
                expiresAt: '2026-12-31T00:00:00Z',
            }),
        } as Partial<ShareService>);
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('h1')!.textContent).toContain('Ops Dashboard');
        expect(el.textContent).toContain('Shared read-only view');
        expect(el.querySelectorAll('section')).toHaveLength(2);
        expect(el.textContent).toContain('cannot be embedded');
        expect(el.textContent).toContain('no longer part of the shared dashboard');
        await expectNoA11yViolations(el);
    });

    it('fetches tile data through the token-fenced public query', async () => {
        const bodies: unknown[] = [];
        const fixture = await create({
            resolve: () => of({
                dashboard: { id: 'd', content: { name: 'D', tiles: [{ widgetId: 'w1', span: 1 }] } },
                widgets: [{ id: 'w1', content: BAR_WIDGET }],
                expiresAt: '2026-12-31T00:00:00Z',
            }),
            query: (_token: string, body: unknown) => {
                bodies.push(body);
                return of({ rows: [{ region: 'EU', sum_amount: 40 }], rowCount: 1, truncated: false });
            },
        } as Partial<ShareService>);
        expect(bodies).toHaveLength(1);
        expect((bodies[0] as { dataset: string }).dataset).toBe('sales_ds');
        // the tile reached ready state: no skeleton/alert remains inside the section
        const section = fixture.nativeElement.querySelector('section')!;
        expect(section.querySelector('inspecto-alert')).toBeNull();
    });
});
