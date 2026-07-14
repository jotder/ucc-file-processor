import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    breakId, Reconciliation, ReconciliationsService, ReconBreak, reconBreakSets,
} from 'app/inspecto/reconciliation';
import { ReconciliationDetailComponent } from './reconciliation-detail.component';
import { ReconExecService } from './recon-exec.service';

/** Same reference fixture as the Board/pure-engine specs: MEA only-A, APAC only-B, EU/data value break. */
const LEFT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 118 },
    { region: 'US', product: 'voice', amount: 50 },
    { region: 'MEA', product: 'voice', amount: 10 },
];
const RIGHT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 114 },
    { region: 'US', product: 'voice', amount: 50 },
    { region: 'APAC', product: 'sms', amount: 7 },
];

const recon = (breaks: ReconBreak[] = []): Reconciliation => ({
    id: 'med_vs_bill', name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily', rightDataset: 'billing_daily',
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
    breaks, lastRunAt: null,
});

async function create(opts: { path?: string; breaks?: ReconBreak[] } = {}) {
    let current = recon(opts.breaks ?? []);
    const save = vi.fn((r: Reconciliation) => ((current = r), of(r)));
    const breaks = vi.fn(async (r: Reconciliation, path?: Record<string, string> | null) =>
        reconBreakSets(r, LEFT, RIGHT, path));
    TestBed.configureTestingModule({
        imports: [ReconciliationDetailComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ActivatedRoute,
                useValue: {
                    snapshot: {
                        paramMap: convertToParamMap({ id: current.id }),
                        queryParamMap: convertToParamMap(opts.path ? { path: opts.path } : {}),
                    },
                },
            },
            { provide: Router, useValue: { navigate: vi.fn(), createUrlTree: () => ({}), serializeUrl: () => '', events: EMPTY } },
            { provide: ReconciliationsService, useValue: { get: () => of(current), save } },
            { provide: ReconExecService, useValue: { breaks } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconciliationDetailComponent);
    fixture.detectChanges();       // ngOnInit — load + compute
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, save, breaks };
}

describe('ReconciliationDetailComponent (Breaks page)', () => {
    it('computes the three live record sets from the exec seam', async () => {
        const { fixture, c } = await create();
        expect(c.missingA().map((b) => b.key)).toEqual(['MEA · voice']);
        expect(c.missingB().map((b) => b.key)).toEqual(['APAC · sms']);
        expect(c.valueBreaks()).toHaveLength(1);
        expect(c.valueBreaks()[0]).toMatchObject({ key: 'EU · data', column: 'amount', leftValue: 118, rightValue: 114 });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Only in A');
        expect(text).toContain('Matched, different');
    });

    it('scopes to the Board dimension path from ?path=', async () => {
        const { c, breaks } = await create({ path: 'region:EU' });
        expect(breaks).toHaveBeenCalledWith(expect.anything(), { region: 'EU' }, null, 'b');
        expect(c.missingA()).toHaveLength(0);
        expect(c.valueBreaks()).toHaveLength(1);
    });

    it('overlays the persisted lifecycle and resolve/re-open persists by identity', async () => {
        const { c, save } = await create();
        const vb = c.valueBreaks()[0];
        expect(vb.status).toBe('open');

        await c.toggleResolve(vb);                       // first touch appends the live break as resolved
        expect(save).toHaveBeenCalledTimes(1);
        expect(c.valueBreaks()[0].status).toBe('resolved');

        await c.toggleResolve(c.valueBreaks()[0]);       // re-open via resolveBreak on the persisted entry
        expect(c.valueBreaks()[0].status).toBe('open');
    });

    it('shows a pre-persisted resolution without any interaction', async () => {
        const resolved: ReconBreak = {
            key: 'EU · data', type: 'value_break', column: 'amount', status: 'resolved', note: 'known billing lag',
        };
        const { c } = await create({ breaks: [resolved] });
        expect(breakId(c.valueBreaks()[0])).toBe(breakId(resolved));
        expect(c.valueBreaks()[0].status).toBe('resolved');
        expect(c.valueBreaks()[0].note).toBe('known billing lag');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
