import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RequirementFormDialog, RequirementFormResult } from './requirement-form.dialog';

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [RequirementFormDialog],
        providers: [provideNoopAnimations(), { provide: MatDialogRef, useValue: ref }],
    });
    const fixture = TestBed.createComponent(RequirementFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('RequirementFormDialog', () => {
    it('blocks submit until title and description are filled', () => {
        const { c, ref } = create();
        c.submit();
        expect(ref.close).not.toHaveBeenCalled();
        c.form.setValue({ title: 'Daily churn KPI', kind: 'kpi', description: 'Track churn by region.' });
        c.submit();
        const result = ref.close.mock.calls[0][0] as RequirementFormResult;
        expect(result).toEqual({ title: 'Daily churn KPI', kind: 'kpi', description: 'Track churn by region.' });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
