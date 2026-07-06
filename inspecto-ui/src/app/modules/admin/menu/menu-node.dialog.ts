import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';

/** A small curated set of menu icons (gamma heroicons) — enough for business menus without a full picker. */
export const MENU_ICON_CHOICES: { value: string; label: string }[] = [
    { value: 'heroicons_outline:banknotes', label: 'Revenue' },
    { value: 'heroicons_outline:shield-exclamation', label: 'Fraud / risk' },
    { value: 'heroicons_outline:chart-bar', label: 'Chart' },
    { value: 'heroicons_outline:chart-pie', label: 'Breakdown' },
    { value: 'heroicons_outline:presentation-chart-line', label: 'Trend' },
    { value: 'heroicons_outline:table-cells', label: 'Table' },
    { value: 'heroicons_outline:map', label: 'Map' },
    { value: 'heroicons_outline:share', label: 'Network' },
    { value: 'heroicons_outline:star', label: 'Highlights' },
    { value: 'heroicons_outline:folder', label: 'Folder' },
    { value: 'heroicons_outline:squares-2x2', label: 'Overview' },
    { value: 'heroicons_outline:phone', label: 'Telecom' },
];

export interface MenuNodeDialogData {
    heading: string;
    title?: string;
    icon?: string;
    /** Existing sibling titles (excluding this node) — for the inline duplicate block. */
    takenTitles: string[];
}

export interface MenuNodeDialogResult {
    title: string;
    icon?: string;
}

/** Add / rename a menu or sub-menu: a name (sibling-unique) + an optional icon. House rule: duplicate name = inline block. */
@Component({
    selector: 'app-menu-node-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ data.heading }}</h2>
        <form [formGroup]="form" (ngSubmit)="save()">
            <mat-dialog-content class="flex flex-col gap-2 pt-1">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="title" cdkFocusInitial placeholder="e.g. Revenue" />
                    @if (form.controls.title.hasError('required')) {
                        <mat-error>A name is required.</mat-error>
                    }
                    @if (form.controls.title.hasError('duplicate')) {
                        <mat-error>A menu with this name already exists here.</mat-error>
                    }
                </mat-form-field>

                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Icon (optional)</mat-label>
                    <mat-select formControlName="icon">
                        <mat-option [value]="null">None</mat-option>
                        @for (opt of icons; track opt.value) {
                            <mat-option [value]="opt.value">
                                <mat-icon class="icon-size-5 mr-2 align-middle" [svgIcon]="opt.value"></mat-icon>
                                {{ opt.label }}
                            </mat-option>
                        }
                    </mat-select>
                </mat-form-field>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="ref.close()">Cancel</button>
                <button mat-flat-button color="primary" type="submit">Save</button>
            </mat-dialog-actions>
        </form>
    `,
})
export class MenuNodeDialog {
    readonly data = inject<MenuNodeDialogData>(MAT_DIALOG_DATA);
    readonly ref = inject<MatDialogRef<MenuNodeDialog, MenuNodeDialogResult>>(MatDialogRef);
    private fb = inject(FormBuilder);
    readonly icons = MENU_ICON_CHOICES;

    readonly form = this.fb.group({
        title: [
            this.data.title ?? '',
            [Validators.required, uniqueNameValidator(() => this.data.takenTitles)],
        ],
        icon: [this.data.icon ?? null],
    });

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { title, icon } = this.form.getRawValue();
        this.ref.close({ title: String(title).trim(), icon: icon ?? undefined });
    }
}
