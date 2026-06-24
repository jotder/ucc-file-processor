import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ImportPreview, SpacesService } from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

/**
 * Dialog input. `spaceId` set ⇒ import a bundle INTO that existing space (with a dry-run preview that
 * surfaces conflicts + structural findings — the bulk-onboarding step). Absent ⇒ create a brand-new
 * space FROM a bundle (no target to preview against, so just id + file).
 */
export interface ImportBundleData {
    spaceId?: string;
}

/** Closed with `true` when something was written (the caller reloads). */
@Component({
    selector: 'app-import-bundle-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        StatusBadgeComponent,
    ],
    template: `
        <h2 mat-dialog-title>{{ isImport ? 'Import bundle into "' + data.spaceId + '"' : 'Create space from bundle' }}</h2>
        <mat-dialog-content class="space-y-4">
            @if (!isImport) {
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>New space id</mat-label>
                    <input matInput [formControl]="newId" required autocomplete="off" />
                    @if (newId.hasError('required') && newId.touched) {
                        <mat-error>Id is required.</mat-error>
                    } @else if (newId.hasError('pattern')) {
                        <mat-error>Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.</mat-error>
                    }
                </mat-form-field>
            }

            <!-- File picker -->
            <div class="flex items-center gap-3">
                <button type="button" mat-stroked-button (click)="picker.click()">
                    <mat-icon svgIcon="heroicons_outline:arrow-up-tray"></mat-icon>
                    <span class="ml-1">Choose bundle (.zip)</span>
                </button>
                <span class="text-secondary truncate">{{ file?.name || 'No file selected' }}</span>
                <input
                    #picker
                    type="file"
                    accept=".zip,application/zip"
                    class="hidden"
                    aria-label="Bundle file (.zip)"
                    (change)="onFile($event)"
                />
            </div>

            @if (previewing) {
                <div class="flex items-center gap-2"><mat-spinner diameter="20"></mat-spinner> <span>Analysing bundle…</span></div>
            }

            <!-- Preview (import-into-existing only) -->
            @if (preview; as p) {
                <div class="space-y-3 rounded-lg border p-3">
                    <div class="flex flex-wrap items-center gap-2">
                        <inspecto-status-badge [value]="p.valid ? 'OK' : 'ERROR'" [label]="p.valid ? 'Valid' : 'Invalid'" />
                        <span class="font-medium">{{ p.kind }}</span>
                        @if (p.sourceSpace) {
                            <span class="text-secondary text-sm">from space “{{ p.sourceSpace }}”</span>
                        }
                    </div>

                    @if (p.dataSources.length) {
                        <div>
                            <div class="text-secondary text-sm font-medium">Data sources</div>
                            <div class="flex flex-wrap gap-1">
                                @for (d of p.dataSources; track d) {
                                    <span class="rounded bg-gray-100 px-2 py-0.5 text-xs dark:bg-gray-700">{{ d }}</span>
                                }
                            </div>
                        </div>
                    }

                    <div class="text-secondary text-sm">{{ p.files.length }} file(s){{ p.hasSpaceToon ? ' · includes space.toon' : '' }}</div>

                    @if (p.conflicts.length) {
                        <div class="space-y-1">
                            <inspecto-status-badge value="WARNING" label="Conflicts" />
                            <div class="text-sm">These data sources already exist in “{{ data.spaceId }}”:</div>
                            <div class="flex flex-wrap gap-1">
                                @for (c of p.conflicts; track c) {
                                    <span class="rounded bg-gray-100 px-2 py-0.5 text-xs dark:bg-gray-700">{{ c }}</span>
                                }
                            </div>
                            <mat-checkbox [formControl]="overwrite">Overwrite existing data sources</mat-checkbox>
                        </div>
                    }

                    @if (findingEntries(p).length) {
                        <div class="space-y-1">
                            <div class="text-secondary text-sm font-medium">Validation findings</div>
                            @for (entry of findingEntries(p); track entry.file) {
                                <div class="text-sm">
                                    <div class="font-mono text-xs">{{ entry.file }}</div>
                                    @for (f of entry.findings; track f.message) {
                                        <div class="flex items-start gap-2 pl-2">
                                            <inspecto-status-badge [value]="f.severity" />
                                            <span>{{ f.fieldPath ? f.fieldPath + ': ' : '' }}{{ f.message }}</span>
                                        </div>
                                    }
                                </div>
                            }
                        </div>
                    }
                </div>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close [disabled]="busy">Cancel</button>
            @if (isImport) {
                <button
                    type="button"
                    mat-flat-button
                    color="primary"
                    [disabled]="!canImport()"
                    (click)="doImport()"
                >
                    Import
                </button>
            } @else {
                <button
                    type="button"
                    mat-flat-button
                    color="primary"
                    [disabled]="!file || newId.invalid || busy"
                    (click)="doCreate()"
                >
                    Create
                </button>
            }
        </mat-dialog-actions>
    `,
})
export class ImportBundleDialog {
    private api = inject(SpacesService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ImportBundleDialog, boolean>);
    readonly data = inject<ImportBundleData>(MAT_DIALOG_DATA);

    readonly isImport = !!this.data.spaceId;
    readonly newId = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9-]{0,62}$/)],
    });
    readonly overwrite = new FormControl(false, { nonNullable: true });

    file: File | null = null;
    preview: ImportPreview | null = null;
    previewing = false;
    busy = false;

    onFile(e: Event): void {
        const input = e.target as HTMLInputElement;
        this.file = input.files?.[0] ?? null;
        this.preview = null;
        this.overwrite.setValue(false);
        if (this.file && this.isImport) this.runPreview();
    }

    private runPreview(): void {
        this.previewing = true;
        this.api.importPreview(this.data.spaceId!, this.file!).subscribe({
            next: (p) => {
                this.previewing = false;
                this.preview = p;
                if (!p.valid) this.toastr.warning('Bundle has validation errors — see findings.');
            },
            error: (err) => {
                this.previewing = false;
                this.toastr.error(apiErrorMessage(err, 'Could not analyse the bundle.'));
            },
        });
    }

    /** Import is allowed once a valid preview exists, and conflicts (if any) are acknowledged. */
    canImport(): boolean {
        if (this.busy || !this.file || !this.preview) return false;
        if (!this.preview.valid) return false;
        if (this.preview.conflicts.length && !this.overwrite.value) return false;
        return true;
    }

    doImport(): void {
        if (!this.canImport()) return;
        this.busy = true;
        this.api.importBundle(this.data.spaceId!, this.file!, this.overwrite.value).subscribe({
            next: (r) => {
                this.busy = false;
                this.toastr.success(
                    `Imported ${r.imported.length} data source(s) into "${this.data.spaceId}"`,
                );
                this.ref.close(true);
            },
            error: (err) => {
                this.busy = false;
                const msg =
                    err?.status === 409
                        ? `Conflicts: ${(err.error?.conflicts ?? []).join(', ')} — enable overwrite.`
                        : apiErrorMessage(err, 'Import failed.');
                this.toastr.error(msg);
            },
        });
    }

    doCreate(): void {
        if (!this.file || this.newId.invalid) {
            this.newId.markAsTouched();
            return;
        }
        this.busy = true;
        this.api.createFromBundle(this.newId.value, this.file).subscribe({
            next: (space) => {
                this.busy = false;
                this.toastr.success(`Space "${space.id}" created from bundle`);
                this.ref.close(true);
            },
            error: (err) => {
                this.busy = false;
                const msg =
                    err?.status === 409
                        ? `A space "${this.newId.value}" already exists.`
                        : apiErrorMessage(err, 'Could not create the space.');
                this.toastr.error(msg);
            },
        });
    }

    findingEntries(p: ImportPreview): { file: string; findings: ImportPreview['findings'][string] }[] {
        return Object.entries(p.findings).map(([file, findings]) => ({ file, findings }));
    }
}
