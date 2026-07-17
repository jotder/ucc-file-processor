import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentsService, DecisionRulesService, JobsService, LensService, RunsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { buildRequirement, decideRequirement } from 'app/inspecto/requirement';
import { RequirementDecisionDialog, RequirementDecisionResult } from './requirement-decision.dialog';

function create(requirement = buildRequirement('Daily churn KPI', 'kpi', 'Track churn.')) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [RequirementDecisionDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: requirement },
            { provide: MatDialogRef, useValue: ref },
            // The "Delivered via" picker's cross-kind suggestion sources.
            { provide: ComponentsService, useValue: { list: (kind: string) => of(kind === 'dashboard' ? [{ name: 'churn_kpi' }] : []) } },
            { provide: RunsService, useValue: { list: () => of([{ name: 'cdr_ingest' }]) } },
            { provide: JobsService, useValue: { list: () => of([]) } },
            { provide: DecisionRulesService, useValue: { list: () => of([]) } },
        ],
    });
    const fixture = TestBed.createComponent(RequirementDecisionDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('RequirementDecisionDialog', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('shows Accept/Reject for a submitted requirement in the default (Builder) lens', () => {
        const { fixture, ref } = create();
        const el = fixture.nativeElement as HTMLElement;
        Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Accept'))?.click();
        const result = ref.close.mock.calls[0][0] as RequirementDecisionResult;
        expect(result).toEqual({ action: 'decide', accept: true, note: undefined });
    });

    it('shows Mark delivered for an accepted requirement', () => {
        const { fixture, ref } = create(decideRequirement(buildRequirement('x', 'kpi', 'y'), true));
        const el = fixture.nativeElement as HTMLElement;
        Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Mark delivered'))?.click();
        expect(ref.close).toHaveBeenCalledWith({ action: 'deliver', note: undefined });
    });

    it('suggests cross-kind component refs on focus and delivers the picked/typed value', async () => {
        const { fixture, c, ref } = create(decideRequirement(buildRequirement('x', 'kpi', 'y'), true));
        c.loadOptions();
        await new Promise((r) => setTimeout(r, 0)); // flush the loader's allSettled chain
        expect(c.filteredOptions().map((o) => o.value)).toEqual(['dashboard/churn_kpi', 'pipeline/cdr_ingest']);

        c.note.setValue('dash'); // typing narrows; the value is never constrained to the list
        expect(c.filteredOptions().map((o) => o.value)).toEqual(['dashboard/churn_kpi']);

        c.note.setValue('dashboard/churn_kpi');
        Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
            .find((b) => b.textContent?.includes('Mark delivered'))?.click();
        expect(ref.close).toHaveBeenCalledWith({ action: 'deliver', note: 'dashboard/churn_kpi' });
    });

    it('hides decision inputs in the Business (read-only) lens', () => {
        const { fixture } = create();
        TestBed.inject(LensService).selectLens('business');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Accept'))).toBe(false);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
