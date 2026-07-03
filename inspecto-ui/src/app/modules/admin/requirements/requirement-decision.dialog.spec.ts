import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LensService } from 'app/inspecto/api';
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
