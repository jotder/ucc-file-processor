import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Reconciliation, ReconciliationsService } from 'app/inspecto/reconciliation';
import { Dataset } from '../studio/datasets/dataset-types';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { ReconciliationDetailComponent } from './reconciliation-detail.component';

const RECON: Reconciliation = {
    id: 'switch_vs_billing', name: 'switch vs billing',
    leftDataset: 'switch_cdr', rightDataset: 'billing_cdr',
    keyColumns: ['id'], compareColumns: [{ column: 'cost_usd', toleranceType: 'absolute', tolerance: 0.02 }],
    breaks: [], lastRunAt: null,
};

const dataset = (id: string): Dataset => ({
    id, name: id, kind: 'physical', sourceName: id, physicalRef: id,
    columns: [{ name: 'id', type: 'number', role: 'dimension' }, { name: 'cost_usd', type: 'number', role: 'measure' }],
    measures: [],
});

function create(recon: Reconciliation = RECON) {
    const save = vi.fn((r: Reconciliation) => of(r));
    let current = recon;
    TestBed.configureTestingModule({
        imports: [ReconciliationDetailComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: recon.id }) } } },
            { provide: ReconciliationsService, useValue: { get: () => of(current), save: (r: Reconciliation) => ((current = r), save(r)) } },
            { provide: DatasetsService, useValue: { get: (id: string) => of(dataset(id)) } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconciliationDetailComponent);
    fixture.detectChanges(); // ngOnInit — load recon + resolve dataset rows
    return { fixture, c: fixture.componentInstance, save };
}

describe('ReconciliationDetailComponent', () => {
    it('runs the reconciliation over the seeded switch/billing rows and reports the breaks', () => {
        const { c } = create();
        c.run();
        const s = c.summary()!;
        // Seed scenario: 2003 value break (cost), 2004 missing_right, 2006 missing_left; 2002 within tolerance.
        expect(s.open).toBe(3);
        expect(s.byType.value_break).toBe(1);
        expect(s.byType.missing_right).toBe(1);
        expect(s.byType.missing_left).toBe(1);
        expect(s.matchedKeys).toBe(4); // 2001,2002,2003,2005
    });

    it('resolving a break moves it out of the open count', async () => {
        const { c } = create();
        c.run();
        const aBreak = c.drillBreaks().find((b) => b.status === 'open')!;
        await c.toggleResolve(aBreak);
        expect(c.summary()!.open).toBe(2);
        expect(c.summary()!.resolved).toBe(1);
    });

    it('re-running after the breaks are already recorded auto-closes nothing new (stable data)', () => {
        const { c } = create();
        c.run();
        const firstOpen = c.summary()!.open;
        c.run();
        expect(c.summary()!.open).toBe(firstOpen);
        expect(c.summary()!.autoClosed).toBe(0);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
