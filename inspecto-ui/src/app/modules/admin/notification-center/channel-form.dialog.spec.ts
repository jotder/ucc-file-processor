import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { NotificationsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ChannelFormData, ChannelFormDialog } from './channel-form.dialog';

function create(data: ChannelFormData, save = vi.fn(() => of({ id: 'x', enabled: true }))) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ChannelFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            { provide: NotificationsService, useValue: { createChannel: save, updateChannel: save } },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(ChannelFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, save };
}

describe('ChannelFormDialog', () => {
    it('creates an email channel, mapping the kind-specific target field', () => {
        const { c, ref, save } = create({});
        c.schemaForm.form.patchValue({ id: 'ops_email', kind: 'EMAIL', target: 'ops@example.com' });
        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({ id: 'ops_email', kind: 'EMAIL', target: 'ops@example.com', enabled: true }),
        );
        expect(ref.close).toHaveBeenCalled();
    });

    it('a webhook channel takes its target from the URL field', () => {
        const { c, save } = create({});
        c.schemaForm.form.patchValue({ id: 'hook', kind: 'WEBHOOK', targetUrl: 'https://h.example.com/x' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ kind: 'WEBHOOK', target: 'https://h.example.com/x' }));
    });

    it('blocks a duplicate id inline on create (case-insensitive)', () => {
        const { c, save } = create({ existingIds: ['ops_email'] });
        c.schemaForm.form.patchValue({ id: 'OPS_Email', kind: 'EMAIL', target: 'a@b.c' });
        c.save();
        expect(save).not.toHaveBeenCalled();
        expect(c.schemaForm.form.get('id')?.hasError('duplicate')).toBe(true);
    });

    it('locks the id on edit and updates the existing channel', () => {
        const { c, save } = create({
            channel: { id: 'hook', kind: 'WEBHOOK', target: 'https://h.example.com/x', enabled: true, createdAt: 1 },
        });
        expect(c.schemaForm.form.get('id')?.disabled).toBe(true);
        c.schemaForm.form.patchValue({ targetUrl: 'https://h.example.com/y' });
        c.save();
        expect(save).toHaveBeenCalledWith('hook', expect.objectContaining({ target: 'https://h.example.com/y' }));
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
