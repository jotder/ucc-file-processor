import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, JobDetail, JobsService, JobType, JobUpsert } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';

/** Dialog input: an existing job ⇒ edit; absent ⇒ create. `focusSchedule` opens with the schedule emphasized
 *  (the "Reschedule" action). */
export interface JobFormData {
    job?: JobDetail;
    focusSchedule?: boolean;
}
export interface JobFormResult {
    saved?: JobDetail;
}

type ScheduleMode = 'cron' | 'event' | 'manual';
const CRON_PRESETS: { label: string; cron: string }[] = [
    { label: 'Every hour', cron: '0 0 * * * *' },
    { label: 'Daily 06:00', cron: '0 0 6 * * *' },
    { label: 'Weekly (Sun 02:00)', cron: '0 0 2 * * 0' },
    { label: 'Monthly (1st 01:00)', cron: '0 0 1 1 * *' },
];

/** A cron expression must be 5 or 6 space-separated fields (matches the backend `CronExpression` parser). */
function validCron(s: string): boolean {
    const n = s.trim().split(/\s+/).filter(Boolean).length;
    return n === 5 || n === 6;
}

/**
 * Create / edit / reschedule a scheduled job. Reactive form (mirrors `connection-form.dialog`): identity +
 * type + schedule mode (cron / on-pipeline / manual) + enabled + a key/value params editor. Mock-served until
 * the real write endpoints land — a 503 surfaces the writes-disabled banner.
 */
@Component({
    selector: 'app-job-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatTooltipModule,
        InspectoAlertComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './job-form.dialog.html',
})
export class JobFormDialog {
    private fb = inject(FormBuilder);
    private api = inject(JobsService);
    private ref = inject(MatDialogRef<JobFormDialog, JobFormResult>);
    private toastr = inject(ToastrService);
    readonly data = inject<JobFormData>(MAT_DIALOG_DATA);

    readonly isEdit = !!this.data.job;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly cronPresets = CRON_PRESETS;
    readonly types: JobType[] = ['ingest', 'enrich', 'report', 'maintenance', 'flow'];

    readonly form = this.fb.group({
        name: [{ value: '', disabled: this.isEdit }, [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
        type: ['ingest' as JobType, Validators.required],
        scheduleMode: ['cron' as ScheduleMode],
        cron: ['0 0 6 * * *'],
        onPipeline: [''],
        enabled: [true],
        params: this.fb.array<FormGroup>([]),
    });

    get paramsArray(): FormArray<FormGroup> {
        return this.form.controls.params;
    }

    constructor() {
        const j = this.data.job;
        if (j) {
            this.form.patchValue({
                name: j.name,
                type: j.type,
                scheduleMode: j.cron ? 'cron' : j.onPipeline ? 'event' : 'manual',
                cron: j.cron ?? '0 0 6 * * *',
                onPipeline: j.onPipeline ?? '',
                enabled: j.enabled,
            });
            for (const [key, value] of Object.entries(j.params ?? {})) this.addParam(key, String(value));
        }
    }

    addParam(key = '', value = ''): void {
        this.paramsArray.push(this.fb.group({ key: [key], value: [value] }));
    }
    removeParam(i: number): void {
        this.paramsArray.removeAt(i);
    }
    applyPreset(cron: string): void {
        this.form.controls.cron.setValue(cron);
    }

    save(): void {
        const v = this.form.getRawValue();
        const mode = v.scheduleMode as ScheduleMode;
        if (this.form.invalid || (mode === 'cron' && !validCron(v.cron ?? '')) || (mode === 'event' && !v.onPipeline?.trim())) {
            this.form.markAllAsTouched();
            return;
        }
        const params: Record<string, unknown> = {};
        for (const g of this.paramsArray.controls) {
            const k = String(g.value.key ?? '').trim();
            if (k) params[k] = g.value.value;
        }
        const body: JobUpsert = {
            name: String(v.name ?? '').trim(),
            type: v.type as JobType,
            cron: mode === 'cron' ? (v.cron ?? '').trim() : null,
            onPipeline: mode === 'event' ? (v.onPipeline ?? '').trim() : null,
            enabled: !!v.enabled,
            params,
        };
        this.saving.set(true);
        const call = this.isEdit ? this.api.update(body.name, body) : this.api.create(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, 'Could not save the job.'));
            },
        });
    }
}
