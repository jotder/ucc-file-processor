import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { NotificationsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { RuleFormData, RuleFormDialog } from './rule-form.dialog';

function create(data: RuleFormData, save = vi.fn(() => of({ id: 'x', enabled: true }))) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [RuleFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            { provide: NotificationsService, useValue: { createRule: save, updateRule: save } },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(RuleFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, save };
}

describe('RuleFormDialog', () => {
    it('creates a rule with a blank minLevel mapped to null (any severity)', () => {
        const { c, ref, save } = create({});
        c.schemaForm.form.patchValue({ id: 'custom1', eventType: 'job.custom', category: 'ops' });
        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({ id: 'custom1', eventType: 'job.custom', category: 'ops', minLevel: null, enabled: true }),
        );
        expect(ref.close).toHaveBeenCalled();
    });

    it('blocks a duplicate id inline on create (case-insensitive)', () => {
        const { c, save } = create({ existingIds: ['custom1'] });
        c.schemaForm.form.patchValue({ id: 'CUSTOM1', eventType: 'job.custom', category: 'ops' });
        c.save();
        expect(save).not.toHaveBeenCalled();
        expect(c.schemaForm.form.get('id')?.hasError('duplicate')).toBe(true);
    });

    it('locks the id on edit and sends the FULL rule (the server PUT is a full replace)', () => {
        const { c, save } = create({
            rule: {
                id: 'custom1', eventType: 'BATCH_FAILED', minLevel: 'WARN', category: 'pipeline',
                titleTemplate: 'T', bodyTemplate: 'B', dedupeKeyTemplate: 'D', enabled: true,
            },
        });
        expect(c.schemaForm.form.get('id')?.disabled).toBe(true);
        c.schemaForm.form.patchValue({ titleTemplate: 'New title' });
        c.save();
        expect(save).toHaveBeenCalledWith('custom1', expect.objectContaining({
            eventType: 'BATCH_FAILED', minLevel: 'WARN', category: 'pipeline', titleTemplate: 'New title',
        }));
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
