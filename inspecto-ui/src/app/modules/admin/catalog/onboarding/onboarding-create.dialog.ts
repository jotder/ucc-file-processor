import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, SpacesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

export interface OnboardingCreateData {
    kind: 'stream' | 'reference';
    /** Pipeline names already in use — the name control rejects a duplicate inline. */
    existingNames?: string[];
}

export interface OnboardingCreateResult {
    /** The created draft's name — the caller navigates to /catalog/onboard/{name}. */
    name?: string;
}

function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Onboard a Stream / Reference — the ask-the-minimum entry point: kind + name (+ optional
 * description). There is no prior config step here — the stages ARE the config, so the name is
 * asked exactly at artifact-creation time. Submitting writes a minimal, spec-valid,
 * `active:false` draft (`POST /config/write`) and registers it (`POST /runs`) so it is
 * catalog-visible immediately; the caller then opens the guided editor. Directory defaults
 * follow the space convention and sit behind Advanced — never blocking the first write.
 */
@Component({
    selector: 'app-onboarding-create-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoAlertComponent,
    ],
    template: `
        <h2 mat-dialog-title>Onboard {{ kind() === 'reference' ? 'Reference' : 'Stream' }}</h2>

        <mat-dialog-content class="!pt-2">
            @if (writesDisabled()) {
                <inspecto-alert class="mb-4 block" variant="warning" icon="heroicons_outline:lock-closed">
                    Config writes are disabled on this server (no write root configured).
                </inspecto-alert>
            }

            <form [formGroup]="form" class="flex flex-col gap-1" (ngSubmit)="create()">
                <mat-button-toggle-group
                    class="mb-4"
                    [value]="kind()"
                    (change)="kind.set($event.value)"
                    aria-label="Data origin kind"
                >
                    <mat-button-toggle value="stream">Stream — event / fact</mat-button-toggle>
                    <mat-button-toggle value="reference">Reference — dimension</mat-button-toggle>
                </mat-button-toggle-group>

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="name" required cdkFocusInitial placeholder="orders_feed" />
                    @if (form.controls.name; as c) {
                        @if (c.hasError('required')) {
                            <mat-error>A name is required.</mat-error>
                        } @else if (c.hasError('pattern')) {
                            <mat-error>Start with a letter or digit; then letters, digits, <code>. _ -</code> only.</mat-error>
                        } @else if (c.hasError('duplicate')) {
                            <mat-error>A pipeline with this name already exists.</mat-error>
                        }
                    }
                </mat-form-field>

                <mat-form-field class="mt-3 w-full" subscriptSizing="dynamic">
                    <mat-label>Description (optional)</mat-label>
                    <input matInput formControlName="description" />
                </mat-form-field>

                <button
                    type="button"
                    class="text-secondary mt-3 flex items-center gap-1 self-start text-sm"
                    (click)="advancedOpen.set(!advancedOpen())"
                    [attr.aria-expanded]="advancedOpen()"
                >
                    <mat-icon
                        class="icon-size-4"
                        [svgIcon]="advancedOpen() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"
                    ></mat-icon>
                    Advanced — directories
                </button>
                @if (advancedOpen()) {
                    <div class="mt-2 flex flex-col gap-1">
                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                            <mat-label>Inbox (poll) directory</mat-label>
                            <input matInput formControlName="poll" />
                            <mat-hint>Where dropped files are collected from.</mat-hint>
                        </mat-form-field>
                        <mat-form-field class="mt-2 w-full" subscriptSizing="dynamic">
                            <mat-label>Database (output) directory</mat-label>
                            <input matInput formControlName="database" />
                        </mat-form-field>
                    </div>
                }
            </form>
        </mat-dialog-content>

        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="creating() || writesDisabled()" (click)="create()">
                Create draft
            </button>
        </mat-dialog-actions>
    `,
})
export class OnboardingCreateDialog {
    private fb = inject(FormBuilder);
    private configApi = inject(ConfigService);
    private spaces = inject(SpacesService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<OnboardingCreateDialog, OnboardingCreateResult>);
    readonly data = inject<OnboardingCreateData>(MAT_DIALOG_DATA);

    readonly kind = signal<'stream' | 'reference'>(this.data.kind);
    readonly advancedOpen = signal(false);
    readonly creating = signal(false);
    readonly writesDisabled = signal(false);

    readonly form = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                ...(this.data.existingNames?.length ? [uniqueNameValidator(this.data.existingNames)] : []),
            ],
        ],
        description: [''],
        poll: [''],
        database: [''],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    constructor() {
        // Directory defaults follow the space convention, live while the author hasn't overridden.
        this.form.controls.name.valueChanges.subscribe((name) => {
            const slug = String(name ?? '').trim();
            if (!slug) return;
            const base = this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
            if (this.form.controls.poll.pristine) this.form.controls.poll.setValue(`${base}/data/inbox/${slug}`, { emitEvent: false });
            if (this.form.controls.database.pristine) this.form.controls.database.setValue(`${base}/data/${slug}/database`, { emitEvent: false });
        });
    }

    create(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        const name = String(v.name ?? '').trim();
        // Beyond the two asked-for dirs, the whole space-convention dir set is derived silently —
        // without dirs.status_dir the run audit never lands (Runs history stays empty), and
        // without processing.duplicate_check the LOCAL poll path re-ingests the same file every
        // cycle (the collector-level `duplicate:` block only drives the collection engine, not
        // the legacy local path) — both found by the P3 live walk.
        const home = (v.database || `data/${name}/database`).replace(/\/database$/, '');
        const config: Record<string, unknown> = {
            name,
            active: false,
            dirs: {
                poll: v.poll || `data/inbox/${name}`,
                database: v.database || `data/${name}/database`,
                backup: `${home}/backup`,
                temp: `${home}/temp`,
                errors: `${home}/errors`,
                quarantine: `${home}/quarantine`,
                markers: `${home}/markers`,
                status_dir: `${home}/status`,
                log_dir: `${home}/logs`,
            },
            processing: {
                threads: 1,
                duplicate_check: { enabled: true, marker_extension: '.processed', retention_days: 30 },
            },
        };
        if (String(v.description ?? '').trim()) config['description'] = String(v.description).trim();
        if (this.kind() === 'reference') config['produces'] = 'reference';

        this.creating.set(true);
        this.configApi.write('pipeline', config).subscribe({
            next: (written) => {
                this.configApi.registerPipeline(written.path).subscribe({
                    next: () => {
                        this.toastr.success(`Draft "${name}" created`);
                        this.ref.close({ name });
                    },
                    error: (e) => {
                        // The file exists — onboarding still opens; the catalog row appears after a restart/rescan.
                        this.toastr.warning(apiErrorMessage(e, 'Draft saved, but registering it failed.'));
                        this.ref.close({ name });
                    },
                });
            },
            error: (e) => {
                this.creating.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else if (e?.status === 409) this.form.controls.name.setErrors({ duplicate: true });
                else this.toastr.error(apiErrorMessage(e, 'Could not create the draft.'));
            },
        });
    }
}
