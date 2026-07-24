import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import { HEROICONS_OUTLINE_IDS, heroiconOutline } from 'app/inspecto/menu/heroicons-outline-ids';

/** A small curated set of menu icons surfaced first (nicer labels); the picker also searches the full set. */
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

/** Full picker option list: the curated few first (with friendly labels), then every other outline icon
 *  (label = its bare id). Deduped so a curated icon isn't repeated. */
const CURATED_VALUES = new Set(MENU_ICON_CHOICES.map((c) => c.value));
const ALL_ICON_OPTIONS: { value: string; label: string }[] = [
    ...MENU_ICON_CHOICES,
    ...HEROICONS_OUTLINE_IDS.map((id) => ({ value: heroiconOutline(id), label: id }))
        .filter((o) => !CURATED_VALUES.has(o.value)),
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
        MatAutocompleteModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
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

                <!-- Searchable icon picker over the full heroicons-outline set (menu-builder-plan O2).
                     Free text filters; only a real icon id is accepted (else the control errors). -->
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Icon (optional)</mat-label>
                    @if (selectedIcon()) {
                        <mat-icon matPrefix class="icon-size-5 ml-1 mr-2" [svgIcon]="selectedIcon()!"></mat-icon>
                    }
                    <input
                        matInput
                        formControlName="icon"
                        [matAutocomplete]="auto"
                        placeholder="Search icons — e.g. chart, phone, shield"
                        aria-label="Menu icon"
                    />
                    <mat-autocomplete #auto="matAutocomplete">
                        @for (opt of filteredIcons(); track opt.value) {
                            <mat-option [value]="opt.value">
                                <mat-icon class="icon-size-5 mr-2 align-middle" [svgIcon]="opt.value"></mat-icon>
                                {{ opt.label }}
                            </mat-option>
                        }
                    </mat-autocomplete>
                    @if (form.controls.icon.hasError('unknownIcon')) {
                        <mat-error>Pick an icon from the list.</mat-error>
                    }
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

    private static readonly VALID_ICONS = new Set(ALL_ICON_OPTIONS.map((o) => o.value));

    /** Blank is allowed (no icon); any non-blank value must be a known icon id. */
    private static iconValidator(c: { value: unknown }): { unknownIcon: true } | null {
        const v = String(c.value ?? '').trim();
        return v === '' || MenuNodeDialog.VALID_ICONS.has(v) ? null : { unknownIcon: true };
    }

    readonly form = this.fb.group({
        title: [
            this.data.title ?? '',
            [Validators.required, uniqueNameValidator(() => this.data.takenTitles)],
        ],
        icon: [this.data.icon ?? '', [MenuNodeDialog.iconValidator]],
    });

    /** The current icon control value as a live signal, for the prefix preview + option filtering. */
    private readonly iconValue = toSignal(this.form.controls.icon.valueChanges, {
        initialValue: this.form.controls.icon.value,
    });

    /** The svgIcon to preview in the field prefix — only when the value is a real icon. */
    readonly selectedIcon = computed(() => {
        const v = String(this.iconValue() ?? '').trim();
        return MenuNodeDialog.VALID_ICONS.has(v) ? v : null;
    });

    /** Options narrowed by the typed text (matches the bare id / label / full value), capped for the panel. */
    readonly filteredIcons = computed(() => {
        const q = String(this.iconValue() ?? '').trim().toLowerCase();
        // When the value is exactly a selected icon, show the full list (so the panel isn't a 1-row dead end).
        const term = MenuNodeDialog.VALID_ICONS.has(q) ? '' : q;
        const matches = term
            ? ALL_ICON_OPTIONS.filter((o) => o.label.toLowerCase().includes(term) || o.value.toLowerCase().includes(term))
            : ALL_ICON_OPTIONS;
        return matches.slice(0, 60);
    });

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { title, icon } = this.form.getRawValue();
        const iconValue = String(icon ?? '').trim();
        this.ref.close({ title: String(title).trim(), icon: iconValue || undefined });
    }
}
