import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { aggregateRecon, Reconciliation, ReconciliationsService } from 'app/inspecto/reconciliation';
import { ReconViewWidgetComponent } from './recon-view-widget.component';
import { ReconExecService } from './recon-exec.service';

const RECON: Reconciliation = {
    id: 'med_vs_bill', name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily', rightDataset: 'billing_daily',
    keyColumns: ['region'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
    breaks: [], lastRunAt: null,
};

const RESULT = aggregateRecon(RECON,
    [{ region: 'EU', amount: 100 }, { region: 'MEA', amount: 10 }],
    [{ region: 'EU', amount: 100 }]);

async function create(opts: { viewId?: string; fail?: boolean } = {}) {
    TestBed.configureTestingModule({
        imports: [ReconViewWidgetComponent],
        providers: [
            provideNoopAnimations(),
            { provide: Router, useValue: { navigate: vi.fn(), createUrlTree: () => ({}), serializeUrl: () => '', events: EMPTY } },
            { provide: ActivatedRoute, useValue: { snapshot: {} } },
            {
                provide: ReconciliationsService,
                useValue: { get: () => (opts.fail ? throwError(() => new Error('nope')) : of(RECON)) },
            },
            { provide: ReconExecService, useValue: { run: vi.fn(async () => RESULT) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconViewWidgetComponent);
    if (opts.viewId) fixture.componentRef.setInput('viewId', opts.viewId);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

describe('ReconViewWidgetComponent', () => {
    it('renders the compact Board for the bound reconciliation', async () => {
        const { fixture, c } = await create({ viewId: RECON.id });
        expect(c.result()).not.toBeNull();
        expect(c.columns().map((col) => col.field)).toEqual(['pct_b_amount', 'pct_b___records']);
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('1 matched');
        expect(text).toContain('1 missing');
    });

    it('shows the empty state when no view is bound and a warning when the load fails', async () => {
        const { fixture } = await create({});
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('No saved Reconciliation');
        // one TestBed per it() — the failure path gets its own spec below
    });

    it('shows a warning when the reconciliation cannot be loaded', async () => {
        const { fixture } = await create({ viewId: 'ghost', fail: true });
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Could not load');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create({ viewId: RECON.id });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
