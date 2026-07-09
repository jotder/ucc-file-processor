import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { runSql } from 'app/inspecto/data-table/sql/sql-run';
import { checkCalculatedExpr, checkCalculatedName } from './calculated-column-guard';
import { CalculatedColumn } from './dataset-types';

/** Preview values from testing one row's expression against a few sample rows, or an error. */
interface TestOutcome {
    index: number;
    values?: unknown[];
    error?: string;
}

/**
 * Calculated-column authoring (DAT-5) — add/edit/remove row-level {@link CalculatedColumn}s
 * (`SELECT *, (expr) AS name` spliced into the dataset's relation server-side) and **test** one against
 * the dataset's sample rows via the same offline AlaSQL path widgets/measures run on, so an expression is
 * verified live with no backend. Inline errors mirror the backend `ExpressionGuard`
 * ({@link checkCalculatedExpr}/{@link checkCalculatedName}) for instant feedback — not authoritative; the
 * server re-validates fail-closed at query time. Presentational, mirrors `DatasetMeasuresComponent`.
 */
@Component({
    selector: 'app-dataset-calculated',
    standalone: true,
    imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-col gap-3">
            @for (c of rows(); track $index; let i = $index) {
                <div class="grid grid-cols-[1fr_2fr_auto_auto] items-end gap-2">
                    <div>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Name</mat-label>
                            <input matInput [ngModel]="c.name" (ngModelChange)="patch(i, { name: $event })" [attr.aria-label]="'Calculated column name ' + (i + 1)" />
                        </mat-form-field>
                        @if (nameError(c.name); as err) {
                            <div class="mt-1 text-xs text-red-600 dark:text-red-400">{{ err }}</div>
                        }
                    </div>
                    <div>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Expression</mat-label>
                            <input matInput [ngModel]="c.expr" (ngModelChange)="patch(i, { expr: $event })" placeholder="e.g. round(amt * 1.1, 2)" [attr.aria-label]="'Calculated column expression ' + (i + 1)" />
                        </mat-form-field>
                        @if (c.expr.trim() && exprError(c.expr); as err) {
                            <div class="mt-1 text-xs text-red-600 dark:text-red-400">{{ err }}</div>
                        }
                    </div>
                    <button mat-stroked-button type="button" (click)="test(i)" [attr.aria-label]="'Test calculated column ' + (i + 1)">Test</button>
                    <button mat-icon-button type="button" (click)="remove(i)" [attr.aria-label]="'Remove calculated column ' + (i + 1)">
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:trash"></mat-icon>
                    </button>
                </div>
                @if (testResult()?.index === i) {
                    <div class="text-secondary -mt-2 text-sm">
                        @if (testResult()?.error) {
                            <span class="text-red-600 dark:text-red-400">{{ testResult()?.error }}</span>
                        } @else {
                            Preview: <span class="font-mono">{{ (testResult()?.values ?? []).join(', ') }}</span>
                        }
                    </div>
                }
            }
            <button mat-stroked-button type="button" class="self-start" (click)="add()">
                <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                <span class="ml-2">Add calculated column</span>
            </button>
        </div>
    `,
})
export class DatasetCalculatedComponent {
    readonly rows = signal<CalculatedColumn[]>([]);
    readonly testResult = signal<TestOutcome | undefined>(undefined);

    @Input({ required: true }) set calculated(c: CalculatedColumn[]) {
        this.rows.set(c);
    }
    @Input({ required: true }) sourceName = '';
    @Input({ required: true }) sampleRows: Record<string, unknown>[] = [];
    @Output() calculatedChange = new EventEmitter<CalculatedColumn[]>();

    nameError(name: string): string | null {
        return checkCalculatedName(name);
    }

    exprError(expr: string): string | null {
        return checkCalculatedExpr(expr);
    }

    patch(index: number, change: Partial<CalculatedColumn>): void {
        const next = this.rows().map((c, i) => (i === index ? { ...c, ...change } : c));
        this.rows.set(next);
        this.calculatedChange.emit(next);
    }

    add(): void {
        const next = [...this.rows(), { name: `calc_${this.rows().length + 1}`, expr: '' }];
        this.rows.set(next);
        this.calculatedChange.emit(next);
    }

    remove(index: number): void {
        const next = this.rows().filter((_, i) => i !== index);
        this.rows.set(next);
        this.calculatedChange.emit(next);
        if (this.testResult()?.index === index) this.testResult.set(undefined);
    }

    /** Run the expression over up to 3 sample rows via the same offline AlaSQL path widgets use. */
    async test(index: number): Promise<void> {
        const col = this.rows()[index];
        const sql = `SELECT ${col.expr} AS test_value FROM ${this.sourceName} LIMIT 3`;
        const res = await runSql(sql, this.sourceName, this.sampleRows);
        this.testResult.set(
            res.ok
                ? { index, values: res.rows.map((r) => r['test_value']) }
                : { index, error: res.error ?? 'Query failed.' },
        );
    }
}
