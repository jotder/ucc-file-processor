import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { DecisionRule, DecisionRulesService, LensService } from 'app/inspecto/api';
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

async function create(opts: { rows?: DecisionRule[]; canAuthor?: boolean; api?: Partial<Record<keyof DecisionRulesService, unknown>> } = {}) {
    const toastr = { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() };
    const api = {
        list: vi.fn(() => of(opts.rows ?? [RULE])),
        simulate: vi.fn(),
        remove: vi.fn(),
        ...opts.api,
    } as unknown as DecisionRulesService;
    TestBed.configureTestingModule({
        imports: [DecisionRulesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: DecisionRulesService, useValue: api },
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
    return { fixture, api, toastr };
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

    it('simulate patches the row and reports the matched preview', async () => {
        const simulated: DecisionRule = { ...RULE, lastSimulation: { matched: 7, total: 1000, checkedAt: 2 } };
        const { fixture, toastr } = await create({ api: { simulate: vi.fn(() => of(simulated)) } });
        fixture.componentInstance.simulate(RULE);
        expect(toastr.info).toHaveBeenCalledWith(expect.stringContaining('7 of 1000'));
        expect(fixture.componentInstance.rows[0].lastSimulation?.matched).toBe(7);
    });

    it('summarizes when-clauses and consequences for the grid', () => {
        expect(summarizeWhen(RULE.when)).toBe('cost_usd > 100 AND duration_s < 60');
        expect(summarizeWhen({ kind: 'group', op: 'AND', items: [] })).toBe('always');
        expect(summarizeConsequences(RULE)).toBe('quarantine→possible fraud pattern · tag→high_risk');
    });
});
