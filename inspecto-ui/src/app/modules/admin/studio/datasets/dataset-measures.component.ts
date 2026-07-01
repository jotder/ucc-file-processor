import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { runSql } from 'app/inspecto/data-table/sql/sql-run';
import { NamedMeasure } from './dataset-types';

/**
 * Calculated-measure authoring — add/edit/remove {@link NamedMeasure}s (a reusable SQL expression a widget
 * can pick as a measure) and **test** one against the dataset's sample rows via the same offline AlaSQL path
 * widgets run on, so an expression is verified live with no backend. Presentational, mirrors `DatasetColumnsComponent`.
 */
@Component({
    selector: 'app-dataset-measures',
    standalone: true,
    imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-col gap-3">
            @for (m of rows(); track m.id; let i = $index) {
                <div class="grid grid-cols-[1fr_1fr_1.5fr_auto_auto] items-end gap-2">
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Id</mat-label>
                        <input matInput [ngModel]="m.id" (ngModelChange)="patch(i, { id: $event })" [attr.aria-label]="'Measure id ' + (i + 1)" />
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Label</mat-label>
                        <input matInput [ngModel]="m.label" (ngModelChange)="patch(i, { label: $event })" [attr.aria-label]="'Measure label ' + (i + 1)" />
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Expression</mat-label>
                        <input matInput [ngModel]="m.expression" (ngModelChange)="patch(i, { expression: $event })" placeholder="e.g. sum(duration_s) / count(*)" [attr.aria-label]="'Measure expression ' + (i + 1)" />
                    </mat-form-field>
                    <button mat-stroked-button type="button" (click)="test(i)" [attr.aria-label]="'Test measure ' + (i + 1)">Test</button>
                    <button mat-icon-button type="button" (click)="remove(i)" [attr.aria-label]="'Remove measure ' + (i + 1)">
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:trash"></mat-icon>
                    </button>
                </div>
                @if (testResult()?.index === i) {
                    <div class="text-secondary -mt-2 text-sm">
                        @if (testResult()?.error) {
                            <span class="text-red-600 dark:text-red-400">{{ testResult()?.error }}</span>
                        } @else {
                            Result: <span class="font-mono">{{ testResult()?.value }}</span>
                        }
                    </div>
                }
            }
            <button mat-stroked-button type="button" class="self-start" (click)="add()">
                <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                <span class="ml-2">Add measure</span>
            </button>
        </div>
    `,
})
export class DatasetMeasuresComponent {
    readonly rows = signal<NamedMeasure[]>([]);
    readonly testResult = signal<{ index: number; value?: unknown; error?: string } | undefined>(undefined);

    @Input({ required: true }) set measures(m: NamedMeasure[]) {
        this.rows.set(m);
    }
    @Input({ required: true }) sourceName = '';
    @Input({ required: true }) sampleRows: Record<string, unknown>[] = [];
    @Output() measuresChange = new EventEmitter<NamedMeasure[]>();

    patch(index: number, change: Partial<NamedMeasure>): void {
        const next = this.rows().map((m, i) => (i === index ? { ...m, ...change } : m));
        this.rows.set(next);
        this.measuresChange.emit(next);
    }

    add(): void {
        const next = [...this.rows(), { id: `measure_${this.rows().length + 1}`, label: 'New measure', expression: 'count(*)' }];
        this.rows.set(next);
        this.measuresChange.emit(next);
    }

    remove(index: number): void {
        const next = this.rows().filter((_, i) => i !== index);
        this.rows.set(next);
        this.measuresChange.emit(next);
        if (this.testResult()?.index === index) this.testResult.set(undefined);
    }

    /** Run the measure's expression against the sample rows via the same offline AlaSQL path widgets use — no
     *  backend needed to verify it before saving. */
    async test(index: number): Promise<void> {
        const measure = this.rows()[index];
        const sql = `SELECT ${measure.expression} AS test_value FROM ${this.sourceName}`;
        const res = await runSql(sql, this.sourceName, this.sampleRows);
        this.testResult.set(
            res.ok ? { index, value: res.rows[0]?.['test_value'] } : { index, error: res.error ?? 'Query failed.' },
        );
    }
}
