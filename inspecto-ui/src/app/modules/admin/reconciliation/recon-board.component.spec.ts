import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { aggregateRecon, Reconciliation, ReconciliationsService } from 'app/inspecto/reconciliation';
import { ReconBoardComponent } from './recon-board.component';
import { ReconExecService } from './recon-exec.service';

const RECON: Reconciliation = {
    id: 'med_vs_bill', name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily', rightDataset: 'billing_daily',
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
    breaks: [], lastRunAt: null,
};

const RESULT = aggregateRecon(RECON, [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 118 },
    { region: 'MEA', product: 'voice', amount: 10 },
], [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 114 },
]);

async function create() {
    const navigate = vi.fn();
    TestBed.configureTestingModule({
        imports: [ReconBoardComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: RECON.id }) } } },
            { provide: Router, useValue: { navigate, createUrlTree: () => ({}), serializeUrl: () => '', events: EMPTY } },
            { provide: ReconciliationsService, useValue: { get: () => of(RECON), save: (r: Reconciliation) => of(r) } },
            { provide: ReconExecService, useValue: { run: vi.fn(async () => RESULT) } },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconBoardComponent);
    fixture.detectChanges();           // ngOnInit — load + auto-run
    await fixture.whenStable();        // the async exec.run
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, navigate };
}

describe('ReconBoardComponent', () => {
    it('loads, auto-runs, and renders the summary + TOTAL strip + board tree', async () => {
        const { fixture, c } = await create();
        expect(c.result()).not.toBeNull();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Mediation vs Billing');
        expect(text).toContain('2 matched');
        expect(text).toContain('only in A: 1');
        expect(text).toContain('value breaks: 1');
        expect(text).toContain('Total');

        // the tree folds region → product, worst-severity first (MEA is structural: only in A)
        expect(c.treeNodes().map((n) => n.label)).toEqual(['MEA', 'EU']);
        expect(c.treeColumns().map((col) => col.field)).toEqual(
            ['a_amount', 'b_amount', 'pct_amount', 'a___records', 'b___records', 'pct___records']);
    });

    it('the details action navigates to the Breaks page with the encoded path', async () => {
        const { c, navigate } = await create();
        c.rowActions[0].onClick!({ __id: 'x', __depth: 0, __hasChildren: false, __expanded: false, __label: 'EU', __path: 'region:EU' });
        expect(navigate).toHaveBeenCalledWith(['/reconciliation', RECON.id, 'breaks'],
            { queryParams: { path: 'region:EU' } });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
