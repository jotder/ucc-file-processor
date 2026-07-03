import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, Space, SpacesService, SpaceTemplateInfo } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';

export interface SpaceTemplateGalleryData {
    /** Space ids already in use — the id control rejects a duplicate inline (product-wide rule). */
    existingIds: string[];
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * "New space from template" gallery (W5) — step 1 picks a Space Template from the vertical cards
 * (Telecom RA / Fraud Management / Financial Auditing / Link Analysis); step 2 asks the minimum
 * (id — pre-filled from the template, unique — plus optional display name/description, both
 * pre-filled) and submits `POST /spaces` with the template id. Closes with the created {@link Space}.
 */
@Component({
    selector: 'app-space-template-gallery-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>New space from template</h2>

        @if (!selected()) {
            <mat-dialog-content>
                @if (loading()) {
                    <inspecto-skeleton [lines]="4" height="6rem" />
                } @else if (templates().length === 0) {
                    <inspecto-empty-state
                        icon="heroicons_outline:square-3-stack-3d"
                        title="No templates available"
                        message="This server publishes no space templates."
                    />
                } @else {
                    <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
                        @for (t of templates(); track t.id) {
                            <button
                                type="button"
                                class="bg-card flex flex-col items-start gap-2 rounded-2xl p-5 text-left shadow transition-shadow hover:shadow-md"
                                (click)="choose(t)"
                                [attr.aria-label]="'Use the ' + t.name + ' template'"
                            >
                                <div class="flex items-center gap-2">
                                    <mat-icon class="text-primary icon-size-5" [svgIcon]="t.icon"></mat-icon>
                                    <span class="font-semibold">{{ t.name }}</span>
                                </div>
                                <span class="text-secondary text-sm">{{ t.tagline }}</span>
                                <div class="flex flex-wrap gap-1">
                                    @for (c of t.contents; track c) {
                                        <span class="text-secondary rounded-full border px-2 py-0.5 text-xs">{{ c }}</span>
                                    }
                                </div>
                            </button>
                        }
                    </div>
                }
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button mat-dialog-close>Cancel</button>
            </mat-dialog-actions>
        } @else {
            <form [formGroup]="form" (ngSubmit)="submit()">
                <mat-dialog-content class="space-y-2">
                    <p class="text-secondary text-sm">
                        {{ selected()!.description }}
                    </p>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Id</mat-label>
                        <input matInput formControlName="id" required autocomplete="off" />
                        <mat-hint>lowercase letters, digits and hyphens — becomes the space folder</mat-hint>
                        @if (form.get('id'); as c) {
                            @if (c.hasError('required')) {
                                <mat-error>Id is required.</mat-error>
                            } @else if (c.hasError('pattern')) {
                                <mat-error>Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.</mat-error>
                            } @else if (c.hasError('duplicate')) {
                                <mat-error>A space with this id already exists.</mat-error>
                            }
                        }
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Display name</mat-label>
                        <input matInput formControlName="display_name" autocomplete="off" />
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Description</mat-label>
                        <input matInput formControlName="description" autocomplete="off" />
                    </mat-form-field>
                </mat-dialog-content>
                <mat-dialog-actions align="end">
                    <button type="button" mat-button (click)="back()">Back</button>
                    <button type="button" mat-button mat-dialog-close>Cancel</button>
                    <button type="submit" mat-flat-button color="primary" [disabled]="form.invalid || saving()">
                        Create space
                    </button>
                </mat-dialog-actions>
            </form>
        }
    `,
})
export class SpaceTemplateGalleryDialog {
    private fb = inject(FormBuilder);
    private api = inject(SpacesService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<SpaceTemplateGalleryDialog, Space>);
    readonly data: SpaceTemplateGalleryData = inject(MAT_DIALOG_DATA);

    readonly loading = signal(true);
    readonly templates = signal<SpaceTemplateInfo[]>([]);
    readonly selected = signal<SpaceTemplateInfo | null>(null);
    readonly saving = signal(false);

    form: FormGroup = this.fb.group({
        id: ['', [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9-]{0,62}$/), uniqueNameValidator(this.data.existingIds)]],
        display_name: [''],
        description: [''],
    });

    constructor() {
        this.api.templates().subscribe({
            next: (t) => {
                this.templates.set(t);
                this.loading.set(false);
            },
            error: (e) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not load space templates.'));
            },
        });
    }

    choose(t: SpaceTemplateInfo): void {
        this.selected.set(t);
        this.form.reset({ id: t.id, display_name: t.name, description: t.tagline });
    }

    back(): void {
        this.selected.set(null);
    }

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.saving.set(true);
        this.api
            .create({
                id: v.id,
                display_name: v.display_name || undefined,
                description: v.description || undefined,
                template: this.selected()!.id,
            })
            .subscribe({
                next: (space) => {
                    this.saving.set(false);
                    this.toastr.success(`Space "${space.id}" created from ${this.selected()!.name}`);
                    this.ref.close(space);
                },
                error: (e) => {
                    this.saving.set(false);
                    const msg =
                        e?.status === 409
                            ? `A space "${v.id}" already exists.`
                            : apiErrorMessage(e, `Could not create "${v.id}".`);
                    this.toastr.error(msg);
                },
            });
    }
}
