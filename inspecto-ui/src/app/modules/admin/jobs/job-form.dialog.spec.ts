import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { JobsService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobFormData, JobFormDialog } from './job-form.dialog';

function create(data: JobFormData, save = vi.fn(() => of({ name: 'x' }))) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [JobFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            { provide: JobsService, useValue: { create: save, update: save } },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(JobFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, save };
}

describe('JobFormDialog', () => {
    it('seeds the form from an existing job (edit) with the id locked', () => {
        const { c } = create({ job: { name: 'j1', type: 'report', cron: '0 0 6 * * *', onPipeline: null, enabled: true, params: { report: 'x' } } });
        expect(c.isEdit).toBe(true);
        expect(c.form.controls.name.disabled).toBe(true);
        expect(c.form.controls.scheduleMode.value).toBe('cron');
        expect(c.paramsArray.length).toBe(1);
    });

    it('blocks save on an invalid cron, then creates on a valid one', () => {
        const { c, ref, save } = create({});
        c.form.patchValue({ name: 'new_job', scheduleMode: 'cron', cron: 'nonsense' });
        c.save();
        expect(save).not.toHaveBeenCalled();
        c.form.patchValue({ cron: '0 0 6 * * *' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: 'new_job', cron: '0 0 6 * * *', onPipeline: null }));
        expect(ref.close).toHaveBeenCalled();
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
