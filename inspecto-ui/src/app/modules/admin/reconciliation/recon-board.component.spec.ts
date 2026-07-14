import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { aggregateRecon, Reconciliation, ReconciliationsService, reconBreakSets } from 'app/inspecto/reconciliation';
import { ReconBoardComponent } from './recon-board.component';
import { ReconExecService } from './recon-exec.service';

const RECON: Reconciliation = {
    id: 'med_vs_bill', name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily', rightDataset: 'billing_daily',
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
    breaks: [], lastRunAt: null,
};

const LEFT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 118 },
    { region: 'MEA', product: 'voice', amount: 10 },
];
const RIGHT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 114 },
];
const RESULT = aggregateRecon(RECON, LEFT, RIGHT);

async function create() {
    const navigate = vi.fn();
    const save = vi.fn((r: Reconciliation) => of(r));
    TestBed.configureTestingModule({
        imports: [ReconBoardComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: RECON.id }) } } },
            { provide: Router, useValue: { navigate, createUrlTree: () => ({}), serializeUrl: () => '', events: EMPTY } },
            { provide: ReconciliationsService, useValue: { get: () => of(RECON), save } },
            {
                provide: ReconExecService,
                useValue: {
                    run: vi.fn(async () => RESULT),
                    breaks: vi.fn(async () => reconBreakSets(RECON, LEFT, RIGHT)),
                },
            },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconBoardComponent);
    fixture.detectChanges();           // ngOnInit — load + auto-run
    await fixture.whenStable();        // the async exec.run
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, navigate, save };
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
            ['a_amount', 'b_amount', 'pct_b_amount', 'a___records', 'b___records', 'pct_b___records']);
    });

    it('a run refreshes the persisted break lifecycle (C9 merge semantics)', async () => {
        const { c, save } = await create();
        expect(save).toHaveBeenCalledTimes(1);
        const persisted = save.mock.calls[0][0] as Reconciliation;
        // MEA/voice only in A + EU/data amount outside 0.5% — both fresh, both open.
        expect(persisted.breaks.map((b) => b.type).sort()).toEqual(['missing_right', 'value_break']);
        expect(persisted.lastRunAt).toBeTruthy();
        expect(c.recon()?.breaks).toHaveLength(2);
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
