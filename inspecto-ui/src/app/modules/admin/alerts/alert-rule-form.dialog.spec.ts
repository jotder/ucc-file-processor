import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AlertRule, AlertsService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AlertRuleFormData, AlertRuleFormDialog } from './alert-rule-form.dialog';

const RULE: AlertRule = {
    name: 'high_error_rate',
    metric: 'error_rate',
    comparator: 'gt',
    threshold: 0.1,
    window: '15m',
    severity: 'CRITICAL',
    onPipeline: 'cdr_ingest',
};

function create(data: AlertRuleFormData, save = vi.fn(() => of(RULE))) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [AlertRuleFormDialog],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(), // the autocomplete option loaders inject root HTTP services
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            { provide: AlertsService, useValue: { createRule: save, updateRule: save } },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(AlertRuleFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, save };
}

describe('AlertRuleFormDialog', () => {
    it('seeds the form from an existing rule (edit) with the id locked', () => {
        const { c } = create({ rule: RULE });
        expect(c.isEdit).toBe(true);
        expect(c.schemaForm.form.get('name')?.disabled).toBe(true);
        expect(c.schemaForm.form.get('metric')?.value).toBe('error_rate');
        expect(c.schemaForm.form.get('window')?.value).toBe('15m');
    });

    it('blocks save while required fields are blank, then creates a valid rule', () => {
        const { c, ref, save } = create({});
        c.save();
        expect(save).not.toHaveBeenCalled();
        c.schemaForm.form.patchValue({ name: 'slow_batch', metric: 'duration_ms', threshold: 30000 });
        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({ name: 'slow_batch', metric: 'duration_ms', threshold: 30000, window: '15m' }),
        );
        expect(ref.close).toHaveBeenCalledWith({ saved: RULE });
    });

    it('blocks save on a duplicate id (case-insensitive), then creates once unique', () => {
        const { c, save } = create({ existingNames: ['high_error_rate'] });
        c.schemaForm.form.patchValue({ name: 'HIGH_Error_Rate', metric: 'error_rate' });
        c.save();
        expect(save).not.toHaveBeenCalled();
        expect(c.schemaForm.form.get('name')?.hasError('duplicate')).toBe(true);
        c.schemaForm.form.patchValue({ name: 'high_error_rate_2' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: 'high_error_rate_2' }));
    });

    it('omits an empty pipeline scope and keeps a set one', () => {
        const { c, save } = create({});
        c.schemaForm.form.patchValue({ name: 'r1', metric: 'error_rate', onPipeline: '' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.not.objectContaining({ onPipeline: expect.anything() }));
        c.schemaForm.form.patchValue({ onPipeline: 'cdr_ingest' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ onPipeline: 'cdr_ingest' }));
    });

    it('a 503 surfaces the writes-disabled banner instead of a toast', () => {
        const { c, fixture } = create({}, vi.fn(() => throwError(() => ({ status: 503 }))));
        c.schemaForm.form.patchValue({ name: 'r1', metric: 'error_rate' });
        c.save();
        fixture.detectChanges();
        expect(c.writesDisabled()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('writes are disabled');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
