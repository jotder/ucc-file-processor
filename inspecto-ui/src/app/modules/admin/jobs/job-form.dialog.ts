import { AfterViewInit, ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, JobDetail, JobsService, JobType, JobUpsert } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { JOB_ATTRIBUTES } from './job-attributes';

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

/**
 * Create / edit / reschedule a scheduled job — the W2 pilot of `<inspecto-schema-form>`: every scalar
 * attribute (identity, type, trigger, arming, catch-up) is declared in {@link JOB_ATTRIBUTES} and
 * rendered by the shared spec-driven form; only the genuinely bespoke pieces stay hand-built here
 * (cron preset quick-pick, the key/value params editor). Mock-served until the real write endpoints
 * land — a 503 surfaces the writes-disabled banner.
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
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './job-form.dialog.html',
})
export class JobFormDialog implements AfterViewInit {
    private fb = inject(FormBuilder);
    private api = inject(JobsService);
    private ref = inject(MatDialogRef<JobFormDialog, JobFormResult>);
    private toastr = inject(ToastrService);
    readonly data = inject<JobFormData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.job;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly cronPresets = CRON_PRESETS;
    readonly attributes = JOB_ATTRIBUTES;

    /** The job's current values, mapped onto the attribute keys (schedule mode is derived). */
    readonly initialValue: Record<string, unknown> | undefined = this.data.job
        ? {
              name: this.data.job.name,
              type: this.data.job.type,
              scheduleMode: (this.data.job.cron ? 'cron' : this.data.job.onPipeline ? 'event' : 'manual') as ScheduleMode,
              cron: this.data.job.cron ?? '0 0 6 * * *',
              onPipeline: this.data.job.onPipeline ?? '',
              enabled: this.data.job.enabled,
              catchUp: !!this.data.job.catchUp,
          }
        : undefined;

    readonly paramsForm = this.fb.group({ params: this.fb.array<FormGroup>([]) });

    get paramsArray(): FormArray<FormGroup> {
        return this.paramsForm.controls.params;
    }

    constructor() {
        for (const [key, value] of Object.entries(this.data.job?.params ?? {})) this.addParam(key, String(value));
    }

    ngAfterViewInit(): void {
        // The id is immutable once created (it is the storage key), like the old hand-built form.
        if (this.isEdit) this.schemaForm.form.get('name')?.disable({ emitEvent: false });
    }

    addParam(key = '', value = ''): void {
        this.paramsArray.push(this.fb.group({ key: [key], value: [value] }));
    }
    removeParam(i: number): void {
        this.paramsArray.removeAt(i);
    }
    applyPreset(cron: string): void {
        this.schemaForm.form.get('cron')?.setValue(cron);
    }

    save(): void {
        if (!this.schemaForm.validate()) return;
        const v = this.schemaForm.value() as {
            name?: string;
            type: JobType;
            scheduleMode: ScheduleMode;
            cron?: string;
            onPipeline?: string;
            enabled?: boolean;
            catchUp?: boolean;
        };
        const params: Record<string, unknown> = {};
        for (const g of this.paramsArray.controls) {
            const k = String(g.value.key ?? '').trim();
            if (k) params[k] = g.value.value;
        }
        const body: JobUpsert = {
            name: this.isEdit ? this.data.job!.name : String(v.name ?? '').trim(),
            type: v.type,
            cron: v.scheduleMode === 'cron' ? String(v.cron ?? '').trim() : null,
            onPipeline: v.scheduleMode === 'event' ? String(v.onPipeline ?? '').trim() : null,
            enabled: v.enabled !== false,
            catchUp: !!v.catchUp,
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
