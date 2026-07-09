import { AfterViewInit, ChangeDetectionStrategy, Component, DestroyRef, inject, signal, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn } from '@angular/forms';
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
import { AttributeSpec } from 'app/inspecto/component-model';
import { JOB_ATTRIBUTES } from './job-attributes';
import { paramDeclsToSpecs } from './job-parameter-specs';

/** Dialog input: an existing job ⇒ edit; absent ⇒ create. `focusSchedule` opens with the schedule emphasized
 *  (the "Reschedule" action). */
export interface JobFormData {
    job?: JobDetail;
    focusSchedule?: boolean;
    /** Ids already in use — on create the name control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
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

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

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
    private destroyRef = inject(DestroyRef);
    readonly data = inject<JobFormData>(MAT_DIALOG_DATA);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;
    @ViewChild('pf') paramForm?: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.job;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly cronPresets = CRON_PRESETS;
    readonly attributes = JOB_ATTRIBUTES;

    /** The selected Job Type's declared parameters (R3), rendered as a typed form (P3c). */
    readonly paramSpecs = signal<AttributeSpec[]>([]);
    /** Existing values for the declared parameters (edit) — patched over the schema-form defaults. */
    readonly paramInitial = signal<Record<string, unknown> | undefined>(undefined);

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
        const nameCtrl = this.schemaForm.form.get('name');
        if (this.isEdit) {
            // The id is immutable once created (it is the storage key), like the old hand-built form.
            nameCtrl?.disable({ emitEvent: false });
        } else if (this.data.existingNames?.length) {
            // Block a duplicate id inline rather than relying on the server 409 (product-wide form rule).
            nameCtrl?.addValidators(uniqueNameValidator(this.data.existingNames));
            nameCtrl?.updateValueAndValidity({ emitEvent: false });
        }
        // Descriptor-driven parameters (P3c): render the selected Job Type's declared params, and follow
        // the type picker so the form re-shapes when the author switches type. Deferred a microtask so the
        // schema-form's async paramSpecs input doesn't mutate during this change-detection pass.
        const typeCtrl = this.schemaForm.form.get('type');
        typeCtrl?.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((t) => this.loadParams(String(t ?? '')));
        queueMicrotask(() => this.loadParams(String(typeCtrl?.value ?? '')));
    }

    /** Fetch the type's descriptor and render its parameters; prune declared keys from the free editor. */
    private loadParams(typeId: string): void {
        if (!typeId) {
            this.paramSpecs.set([]);
            this.paramInitial.set(undefined);
            return;
        }
        this.api.describeType(typeId).subscribe({
            next: (d) => {
                const specs = paramDeclsToSpecs(d.parameters);
                this.paramSpecs.set(specs);
                const init: Record<string, unknown> = {};
                for (const s of specs) {
                    const v = this.data.job?.params?.[s.key];
                    if (v !== undefined) init[s.key] = v;
                }
                this.paramInitial.set(Object.keys(init).length ? init : undefined);
                // Declared params own their typed field — drop any duplicate from the free key/value editor.
                const declared = new Set(specs.map((s) => s.key));
                for (let i = this.paramsArray.length - 1; i >= 0; i--) {
                    if (declared.has(String(this.paramsArray.at(i).value.key ?? '').trim())) this.paramsArray.removeAt(i);
                }
            },
            error: () => {
                // Unknown type / no descriptor (e.g. a legacy type or 404) → free key/value editor only.
                this.paramSpecs.set([]);
                this.paramInitial.set(undefined);
            },
        });
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
        if (this.paramForm && !this.paramForm.validate()) return;
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
        // Declared (descriptor-driven) parameters first — blank optionals are omitted.
        if (this.paramForm) {
            for (const [k, val] of Object.entries(this.paramForm.value())) {
                if (val !== null && val !== undefined && val !== '') params[k] = val;
            }
        }
        // Then any additional key/value params the author added by hand (never overriding a declared one).
        for (const g of this.paramsArray.controls) {
            const k = String(g.value.key ?? '').trim();
            if (k && !(k in params)) params[k] = g.value.value;
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
