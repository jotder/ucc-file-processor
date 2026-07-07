import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { forkJoin, of, switchMap } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { environment } from 'environments/environment';
import { apiErrorMessage, Branding, BrandingService, Space, SpacesService } from 'app/inspecto/api';

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/** Derive a SpaceId-legal slug from a display name: lowercase, non-alphanumeric → hyphen, trimmed, ≤ 63. */
function slugify(name: string): string {
    return name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+/, '')
        .slice(0, 63)
        .replace(/-+$/, '');
}

/** Max logo size — keeps the per-space settings document small (logos are stored inline as data-URLs). */
const MAX_LOGO_BYTES = 200 * 1024;

/** Dialog input — pass an existing space to edit it; omit to create a new one. */
export interface SpaceFormData {
    space?: Space;
}

/**
 * Create a new space or edit an existing one, including its branding (logo, caption, footer). On create
 * the user names the space (the SpaceId folder is auto-derived from the name, editable under "Edit id"),
 * fixing the old "type a name → Create stays disabled" trap. The id is immutable on edit. Persists via
 * POST/PUT /spaces and, for branding, PUT /spaces/{id}/settings/branding. Closes with the resulting
 * {@link Space}. The `default` space is not editable and is never opened here in edit mode.
 */
@Component({
    selector: 'app-space-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
    ],
    template: `
        <h2 mat-dialog-title>{{ editMode ? 'Edit space' : 'New space' }}</h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="space-y-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Display name</mat-label>
                    <input matInput formControlName="display_name" required autocomplete="off" />
                    @if (form.get('display_name')?.hasError('required')) {
                        <mat-error>A name is required.</mat-error>
                    }
                </mat-form-field>

                @if (editMode) {
                    <div class="text-secondary text-xs">Id: <span class="font-mono">{{ data?.space?.id }}</span> — cannot be changed.</div>
                } @else if (!editId()) {
                    <div class="text-secondary flex items-center gap-2 text-xs">
                        <span>Id: <span class="font-mono">{{ form.get('id')?.value || '—' }}</span></span>
                        <button type="button" mat-button (click)="editId.set(true)">Edit id</button>
                    </div>
                } @else {
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Id</mat-label>
                        <input matInput formControlName="id" autocomplete="off" (input)="idManual = true" />
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
                }

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <input matInput formControlName="description" autocomplete="off" />
                </mat-form-field>

                <!-- Branding -->
                <div class="flex flex-col gap-2 border-t pt-3">
                    <span id="space-logo-label" class="text-sm font-medium">Logo</span>
                    <div class="flex items-center gap-4">
                        <div class="bg-hover flex h-14 w-28 items-center justify-center rounded border p-2">
                            <img class="max-h-full max-w-full" [src]="logoDataUrl() || defaultLogo" alt="Logo preview" />
                        </div>
                        <div class="flex flex-col gap-1">
                            <input #logoInput type="file" accept="image/*" class="sr-only"
                                   aria-labelledby="space-logo-label" (change)="onLogoSelected(logoInput)" />
                            <button mat-stroked-button type="button" (click)="logoInput.click()">
                                <mat-icon svgIcon="heroicons_outline:arrow-up-tray"></mat-icon>
                                <span class="ml-2">Upload</span>
                            </button>
                            @if (logoDataUrl()) {
                                <button mat-button type="button" (click)="removeLogo()">Remove custom logo</button>
                            }
                        </div>
                    </div>
                    @if (logoError()) {
                        <span class="text-warn text-xs" role="alert">{{ logoError() }}</span>
                    }
                </div>

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Caption</mat-label>
                    <input matInput formControlName="caption" autocomplete="off" placeholder="Unveil stories from your data" />
                    <mat-hint>Shown beneath the logo in the sidebar.</mat-hint>
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Footer text</mat-label>
                    <input matInput formControlName="footerText" autocomplete="off" />
                </mat-form-field>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button mat-dialog-close>Cancel</button>
                <button type="submit" mat-flat-button color="primary" [disabled]="form.invalid || saving">
                    {{ editMode ? 'Save' : 'Create' }}
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class SpaceFormDialog {
    private fb = inject(FormBuilder);
    private api = inject(SpacesService);
    private brandingApi = inject(BrandingService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<SpaceFormDialog, Space>);
    protected data = inject<SpaceFormData | null>(MAT_DIALOG_DATA);

    readonly editMode = !!this.data?.space;
    readonly defaultLogo = environment.appLogo;

    saving = false;
    /** Whether the id field is being shown/overridden manually (create mode only). */
    readonly editId = signal(false);
    idManual = false;
    /** Uploaded logo as a data-URL, or `null` to use the default. */
    readonly logoDataUrl = signal<string | null>(null);
    readonly logoError = signal<string | null>(null);

    form: FormGroup = this.fb.group({
        id: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[a-z0-9][a-z0-9-]{0,62}$/),
                uniqueNameValidator(this.api.availableSpaces().map((s) => s.id)),
            ],
        ],
        display_name: ['', [Validators.required]],
        description: [''],
        caption: [''],
        footerText: [''],
    });

    constructor() {
        const s = this.data?.space;
        if (s) {
            // Edit mode: the id is immutable, so drop it from validation and prefill the rest + branding.
            this.form.removeControl('id');
            this.form.patchValue({ display_name: s.displayName, description: s.description });
            this.brandingApi.getFor(s.id).subscribe({
                next: (b) => {
                    this.logoDataUrl.set(b.logoDataUrl);
                    this.form.patchValue({ caption: b.caption ?? '', footerText: b.footerText ?? '' });
                },
                error: () => undefined, // degrade gracefully — branding fields start empty
            });
        } else {
            // Create mode: keep the id slug in sync with the name until the user overrides it.
            this.form.controls['display_name'].valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => {
                if (!this.idManual) this.form.controls['id'].setValue(slugify(String(v ?? '')), { emitEvent: false });
            });
        }
    }

    onLogoSelected(input: HTMLInputElement): void {
        this.logoError.set(null);
        const file = input.files?.[0];
        input.value = ''; // allow re-selecting the same file
        if (!file) return;
        if (!file.type.startsWith('image/')) {
            this.logoError.set('Please choose an image file.');
            return;
        }
        if (file.size > MAX_LOGO_BYTES) {
            this.logoError.set('Image is too large (max 200 KB).');
            return;
        }
        const reader = new FileReader();
        reader.onload = () => this.logoDataUrl.set(reader.result as string);
        reader.onerror = () => this.logoError.set('Could not read that file.');
        reader.readAsDataURL(file);
    }

    removeLogo(): void {
        this.logoDataUrl.set(null);
        this.logoError.set(null);
    }

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        const branding: Branding = {
            logoDataUrl: this.logoDataUrl(),
            caption: String(v.caption ?? '').trim() || null,
            footerText: String(v.footerText ?? '').trim() || null,
        };
        this.saving = true;

        const existing = this.data?.space;
        const save$ = existing
            ? this.api.update(existing.id, { display_name: v.display_name, description: v.description || undefined })
            : this.api.create({ id: v.id, display_name: v.display_name || undefined, description: v.description || undefined });

        save$
            .pipe(switchMap((space) => forkJoin({ space: of(space), _: this.brandingApi.saveFor(space.id, branding) })))
            .subscribe({
                next: ({ space }) => {
                    this.saving = false;
                    this.toastr.success(`Space "${space.id}" ${existing ? 'updated' : 'created'}`);
                    this.ref.close(space);
                },
                error: (e) => {
                    this.saving = false;
                    const id = existing?.id ?? v.id;
                    const msg =
                        e?.status === 409
                            ? `A space "${id}" already exists.`
                            : apiErrorMessage(e, `Could not ${existing ? 'update' : 'create'} "${id}".`);
                    this.toastr.error(msg);
                },
            });
    }
}
