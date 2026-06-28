import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { DatasetColumn, DatasetRole } from './dataset-types';

const ROLES: DatasetRole[] = ['dimension', 'metric', 'temporal'];

/**
 * Presentational role/format tagger for a dataset's columns (seeded by `inferRoles`). One row per column:
 * its inferred type, an editable **role** (dimension / metric / temporal — what a chart may bind it to), an
 * optional display **label** and **format**. No HTTP / services — emits the edited array to the editor host.
 */
@Component({
    selector: 'app-dataset-columns',
    standalone: true,
    imports: [FormsModule, MatFormFieldModule, MatInputModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (rows().length === 0) {
            <p class="text-secondary text-sm">No columns yet — pick a source to infer columns.</p>
        } @else {
            <table class="w-full text-sm">
                <thead>
                    <tr class="text-secondary text-left text-xs uppercase tracking-wider">
                        <th scope="col" class="py-1 pr-3 font-semibold">Column</th>
                        <th scope="col" class="py-1 pr-3 font-semibold">Type</th>
                        <th scope="col" class="py-1 pr-3 font-semibold">Role</th>
                        <th scope="col" class="py-1 pr-3 font-semibold">Label</th>
                        <th scope="col" class="py-1 font-semibold">Format</th>
                    </tr>
                </thead>
                <tbody>
                    @for (col of rows(); track col.name) {
                        <tr>
                            <td class="py-1 pr-3 font-mono">{{ col.name }}</td>
                            <td class="py-1 pr-3">
                                <span class="bg-card rounded px-2 py-0.5 font-mono text-xs">{{ col.type }}</span>
                            </td>
                            <td class="py-1 pr-3">
                                <mat-form-field subscriptSizing="dynamic" class="w-32">
                                    <mat-select
                                        [ngModel]="col.role"
                                        (ngModelChange)="patch(col.name, { role: $event })"
                                        [aria-label]="'Role for ' + col.name"
                                    >
                                        @for (r of roles; track r) {
                                            <mat-option [value]="r">{{ r }}</mat-option>
                                        }
                                    </mat-select>
                                </mat-form-field>
                            </td>
                            <td class="py-1 pr-3">
                                <mat-form-field subscriptSizing="dynamic" class="w-40">
                                    <input
                                        matInput
                                        [ngModel]="col.label ?? ''"
                                        (ngModelChange)="patch(col.name, { label: $event })"
                                        [attr.aria-label]="'Label for ' + col.name"
                                        [placeholder]="col.name"
                                    />
                                </mat-form-field>
                            </td>
                            <td class="py-1">
                                <mat-form-field subscriptSizing="dynamic" class="w-28">
                                    <input
                                        matInput
                                        [ngModel]="col.format ?? ''"
                                        (ngModelChange)="patch(col.name, { format: $event })"
                                        [attr.aria-label]="'Format for ' + col.name"
                                        placeholder="—"
                                    />
                                </mat-form-field>
                            </td>
                        </tr>
                    }
                </tbody>
            </table>
        }
    `,
})
export class DatasetColumnsComponent {
    readonly roles = ROLES;
    readonly rows = signal<DatasetColumn[]>([]);

    @Input({ required: true }) set columns(cols: DatasetColumn[]) {
        this.rows.set(cols);
    }
    @Output() columnsChange = new EventEmitter<DatasetColumn[]>();

    patch(name: string, change: Partial<DatasetColumn>): void {
        const next = this.rows().map((c) => (c.name === name ? { ...c, ...change } : c));
        this.rows.set(next);
        this.columnsChange.emit(next);
    }
}
