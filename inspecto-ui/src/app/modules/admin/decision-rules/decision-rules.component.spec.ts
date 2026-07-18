import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AssistService, DbBrowserService, DecisionRule, DecisionRulesService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { DecisionRulesComponent, summarizeConsequences, summarizeWhen } from './decision-rules.component';

const RULE: DecisionRule = {
    name: 'quarantine_high_cost',
    description: 'Suspicious high-cost calls',
    targetType: 'pipeline',
    target: 'cdr_ingest',
    when: {
        kind: 'group',
        op: 'AND',
        items: [
            { kind: 'condition', field: 'cost_usd', operator: '>', value: '100' },
            { kind: 'condition', field: 'duration_s', operator: '<', value: '60' },
        ],
    },
    consequences: [
        { action: 'quarantine', destination: 'possible fraud pattern' },
        { action: 'tag', destination: 'high_risk' },
    ],
    priority: 20,
    enabled: true,
    lastSimulation: null,
    createdAt: 1,
    updatedAt: 1,
};

async function create(
    opts: {
        rows?: DecisionRule[];
        canAuthor?: boolean;
        api?: Partial<Record<keyof DecisionRulesService, unknown>>;
        /** Sample rows the target-store fetch returns; `'error'` = no browsable store for the target. */
        sample?: Record<string, unknown>[] | 'error';
    } = {},
) {
    const toastr = { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() };
    const api = {
        list: vi.fn(() => of(opts.rows ?? [RULE])),
        simulate: vi.fn(),
        remove: vi.fn(),
        ...opts.api,
    } as unknown as DecisionRulesService;
    const table = vi.fn(() =>
        opts.sample === 'error'
            ? throwError(() => new Error('no store'))
            : of({ columns: [], rows: opts.sample ?? [], statistics: { rowCount: 0, elapsedMs: 0, truncated: false } }),
    );
    const db = { table } as unknown as DbBrowserService;
    TestBed.configureTestingModule({
        imports: [DecisionRulesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: DecisionRulesService, useValue: api },
            { provide: DbBrowserService, useValue: db },
            { provide: AssistService, useValue: { run: vi.fn(() => of({ data: { consequences: [] } })) } },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: {} },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(opts.canAuthor !== false) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(DecisionRulesComponent);
    fixture.detectChanges();
    return { fixture, api, toastr, table };
}

describe('DecisionRulesComponent', () => {
    it('lists rules with no a11y violations', async () => {
        const { fixture } = await create();
        expect(fixture.componentInstance.rows).toEqual([RULE]);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the empty state with no a11y violations', async () => {
        const { fixture } = await create({ rows: [] });
        expect(fixture.nativeElement.textContent).toContain('No decision rules yet');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('hides authoring actions outside the Builder lens (capability seam)', async () => {
        const { fixture } = await create({ canAuthor: false });
        expect(fixture.componentInstance.rowActions.map((a) => a.hint)).toEqual(['Simulate (dry run)']);
        expect(fixture.nativeElement.textContent).not.toContain('New decision rule');
    });

    it('simulate fetches a target sample, patches the row, and reports the matched preview', async () => {
        const sample = [{ cost_usd: 250, duration_s: 30 }];
        const simulated: DecisionRule = { ...RULE, lastSimulation: { matched: 1, total: 1, checkedAt: 2 } };
        const simulate = vi.fn(() => of(simulated));
        const { fixture, toastr, table } = await create({ sample, api: { simulate } });
        fixture.componentInstance.simulate(RULE);
        expect(table).toHaveBeenCalledWith(expect.objectContaining({ name: 'cdr_ingest' }));
        expect(simulate).toHaveBeenCalledWith('quarantine_high_cost', sample);
        expect(toastr.info).toHaveBeenCalledWith(expect.stringContaining('1 of 1'));
        expect(fixture.componentInstance.rows[0].lastSimulation?.matched).toBe(1);
    });

    it('simulate over a target with no browsable store falls back to an empty sample', async () => {
        const simulated: DecisionRule = { ...RULE, lastSimulation: { matched: 0, total: 0, checkedAt: 2 } };
        const simulate = vi.fn(() => of(simulated));
        const { fixture, toastr } = await create({ sample: 'error', api: { simulate } });
        fixture.componentInstance.simulate(RULE);
        expect(simulate).toHaveBeenCalledWith('quarantine_high_cost', []);
        expect(toastr.info).toHaveBeenCalledWith(expect.stringContaining('no records found'));
    });

    it('summarizes when-clauses and consequences for the grid', () => {
        expect(summarizeWhen(RULE.when)).toBe('cost_usd > 100 AND duration_s < 60');
        expect(summarizeWhen({ kind: 'group', op: 'AND', items: [] })).toBe('always');
        expect(summarizeConsequences(RULE)).toBe('Quarantine (possible fraud pattern) · Tag "high_risk"');
    });
});
