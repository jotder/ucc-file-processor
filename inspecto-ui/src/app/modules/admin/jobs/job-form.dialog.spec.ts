import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { JobsService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobFormData, JobFormDialog } from './job-form.dialog';

const noParams = () => of({ id: 'x', title: '', description: '', parameters: [], emits: [], artifacts: [] });

function create(data: JobFormData, save = vi.fn(() => of({ name: 'x' })), describeType = vi.fn(noParams)) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [JobFormDialog],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(), // the autocomplete option loaders inject root HTTP services
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            { provide: JobsService, useValue: { create: save, update: save, describeType, types: () => of([]) } },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(JobFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, save };
}

describe('JobFormDialog', () => {
    it('seeds the form from an existing job (edit) and stays on the config step (id is immutable)', () => {
        const { c } = create({ job: { name: 'j1', type: 'report', cron: '0 0 6 * * *', onPipeline: null, enabled: true, params: { report: 'x' } } });
        expect(c.isEdit).toBe(true);
        expect(c.step()).toBe('config');
        expect(c.schemaForm.form.get('scheduleMode')?.value).toBe('cron');
        expect(c.paramsArray.length).toBe(1);
    });

    it('blocks save on an invalid cron, advances to the save step once valid, then creates with the entered id', () => {
        const { c, ref, save } = create({});
        c.schemaForm.form.patchValue({ scheduleMode: 'cron', cron: 'nonsense' });
        c.save();
        expect(c.step()).toBe('config');
        expect(save).not.toHaveBeenCalled();

        c.schemaForm.form.patchValue({ cron: '0 0 6 * * *' });
        c.save(); // config valid ⇒ advances to the save step, doesn't call the API yet
        expect(c.step()).toBe('save');
        expect(save).not.toHaveBeenCalled();

        c.saveForm.patchValue({ name: 'new_job' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: 'new_job', cron: '0 0 6 * * *', onPipeline: null }));
        expect(ref.close).toHaveBeenCalled();
    });

    it('blocks save on a duplicate id (case-insensitive) at the save step, then creates once unique', () => {
        const { c, save } = create({ existingNames: ['cdr_ingest'] });
        c.schemaForm.form.patchValue({ scheduleMode: 'manual' });
        c.save();
        expect(c.step()).toBe('save');

        c.saveForm.patchValue({ name: 'CDR_Ingest' });
        c.save();
        expect(save).not.toHaveBeenCalled();
        expect(c.saveForm.get('name')?.hasError('duplicate')).toBe(true);

        c.saveForm.patchValue({ name: 'cdr_ingest_2' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: 'cdr_ingest_2' }));
    });

    it('lets Back return to the config step without losing the entered id', () => {
        const { c } = create({});
        c.schemaForm.form.patchValue({ scheduleMode: 'manual' });
        c.save();
        expect(c.step()).toBe('save');
        c.saveForm.patchValue({ name: 'kept_id' });
        c.backToConfig();
        expect(c.step()).toBe('config');
        expect(c.saveForm.get('name')?.value).toBe('kept_id');
    });

    it("renders the selected type's declared parameters and includes them on save", async () => {
        const describeType = vi.fn(() =>
            of({
                id: 'sql.template', title: 'Templated SQL', description: '',
                parameters: [{ name: 'sink_dataset', type: 'STRING', required: true, deduce: '', default: '', description: 'Output Dataset' }],
                emits: [], artifacts: [],
            }),
        );
        const { c, fixture, save } = create({}, undefined, describeType);
        await Promise.resolve(); // flush the queued loadParams microtask
        fixture.detectChanges();

        expect(c.paramSpecs().map((s) => s.key)).toContain('sink_dataset');
        c.schemaForm.form.patchValue({ scheduleMode: 'manual' });
        c.paramForm!.form.patchValue({ sink_dataset: 'txn_rollup' });
        c.save(); // → save step
        c.saveForm.patchValue({ name: 'rollup' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ params: expect.objectContaining({ sink_dataset: 'txn_rollup' }) }));
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
