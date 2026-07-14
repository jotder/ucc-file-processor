import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Dataset } from '../studio/datasets/dataset-types';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { CompareColumn, DEFAULT_BANDS, MeasureAgg, ReconBands, Reconciliation, ToleranceType } from 'app/inspecto/reconciliation';

export interface ReconciliationFormResult {
    name: string;
    leftDataset: string;
    rightDataset: string;
    thirdDataset?: string;
    keyColumns: string[];
    compareColumns: CompareColumn[];
    bands: ReconBands;
}

/** Optional dialog data — pass an existing reconciliation to edit it in place, or duplicate-and-rebind. */
export interface ReconciliationFormData {
    recon?: Reconciliation;
    /** Prefill from `recon` but create a NEW reconciliation (the template flow — design §8). */
    duplicate?: boolean;
}

interface CompareRow {
    column: string;
    agg: MeasureAgg;
    toleranceType: ToleranceType;
    tolerance: number;
}

/** Define (or edit) a Dataset-vs-Dataset reconciliation — both sides, the key column(s) whose order is
 *  the Board hierarchy, the compare column(s) each with an aggregation + tolerance, and the Board's
 *  severity bands. Column pickers are driven by the left dataset's declared columns. */
@Component({
    selector: 'app-reconciliation-form-dialog',
    standalone: true,
    imports: [FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ editing ? 'Edit reconciliation' : duplicating ? 'Duplicate reconciliation' : 'New reconciliation' }}</h2>
        <mat-dialog-content class="flex flex-col gap-3">
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Name</mat-label>
                <input matInput [(ngModel)]="name" placeholder="e.g. switch vs billing" />
            </mat-form-field>

            <div class="flex gap-3">
                <mat-form-field class="flex-1" subscriptSizing="dynamic">
                    <mat-label>Left dataset (anchor / source of truth)</mat-label>
                    <mat-select [(ngModel)]="leftDataset" (ngModelChange)="onLeftChange($event)">
                        @for (d of datasets(); track d.id) { <mat-option [value]="d.id">{{ d.name }}</mat-option> }
                    </mat-select>
                </mat-form-field>
                <mat-form-field class="flex-1" subscriptSizing="dynamic">
                    <mat-label>Right dataset</mat-label>
                    <mat-select [(ngModel)]="rightDataset">
                        @for (d of datasets(); track d.id) { <mat-option [value]="d.id">{{ d.name }}</mat-option> }
                    </mat-select>
                </mat-form-field>
            </div>

            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Third dataset (optional — 3-way vs the anchor)</mat-label>
                <mat-select [(ngModel)]="thirdDataset">
                    <mat-option [value]="''">— none (2-way) —</mat-option>
                    @for (d of datasets(); track d.id) { <mat-option [value]="d.id">{{ d.name }}</mat-option> }
                </mat-select>
            </mat-form-field>

            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Key column(s) — selection order is the Board tree</mat-label>
                <mat-select [(ngModel)]="keyColumns" multiple>
                    @for (c of leftColumns(); track c) { <mat-option [value]="c">{{ c }}</mat-option> }
                </mat-select>
            </mat-form-field>

            <div class="flex items-center justify-between">
                <span class="text-secondary text-xs font-semibold uppercase tracking-wider">Compare columns</span>
                <button mat-stroked-button type="button" (click)="addCompare()" [disabled]="!leftColumns().length">
                    <mat-icon svgIcon="heroicons_outline:plus"></mat-icon><span class="ml-1">Add</span>
                </button>
            </div>
            @for (row of compareRows(); track $index; let i = $index) {
                <div class="flex items-center gap-2">
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Column</mat-label>
                        <mat-select [(ngModel)]="row.column">
                            @for (c of leftColumns(); track c) { <mat-option [value]="c">{{ c }}</mat-option> }
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field class="w-24" subscriptSizing="dynamic">
                        <mat-label>Agg</mat-label>
                        <mat-select [(ngModel)]="row.agg">
                            <mat-option value="sum">sum</mat-option>
                            <mat-option value="count">count</mat-option>
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field class="w-32" subscriptSizing="dynamic">
                        <mat-label>Tolerance</mat-label>
                        <mat-select [(ngModel)]="row.toleranceType">
                            <mat-option value="exact">exact</mat-option>
                            <mat-option value="absolute">± absolute</mat-option>
                            <mat-option value="percent">± percent</mat-option>
                        </mat-select>
                    </mat-form-field>
                    @if (row.toleranceType !== 'exact') {
                        <mat-form-field class="w-24" subscriptSizing="dynamic">
                            <mat-label>Value</mat-label>
                            <input matInput type="number" [(ngModel)]="row.tolerance" />
                        </mat-form-field>
                    }
                    <button mat-icon-button type="button" (click)="removeCompare(i)" aria-label="Remove compare column">
                        <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                    </button>
                </div>
            }

            <span class="text-secondary text-xs font-semibold uppercase tracking-wider">Board bands (|Δ%|)</span>
            <div class="flex items-center gap-3">
                <mat-form-field class="w-32" subscriptSizing="dynamic">
                    <mat-label>Warn from %</mat-label>
                    <input matInput type="number" min="0" [(ngModel)]="warnPct" />
                </mat-form-field>
                <mat-form-field class="w-32" subscriptSizing="dynamic">
                    <mat-label>Breach over %</mat-label>
                    <input matInput type="number" min="0" [(ngModel)]="breachPct" />
                </mat-form-field>
                <span class="text-secondary text-xs">ok &lt; warn · warn–breach · breach &gt;</span>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" [disabled]="!valid()" (click)="submit()">
                {{ editing ? 'Save' : 'Create' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class ReconciliationFormDialog {
    private datasetsApi = inject(DatasetsService);
    private ref = inject(MatDialogRef<ReconciliationFormDialog, ReconciliationFormResult>);
    private data = inject<ReconciliationFormData | null>(MAT_DIALOG_DATA, { optional: true });

    readonly duplicating = !!this.data?.recon && !!this.data?.duplicate;
    readonly editing = !!this.data?.recon && !this.data?.duplicate;
    readonly datasets = signal<Dataset[]>([]);
    readonly leftColumns = signal<string[]>([]);
    readonly compareRows = signal<CompareRow[]>([]);

    name = '';
    leftDataset = '';
    rightDataset = '';
    thirdDataset = '';
    keyColumns: string[] = [];
    warnPct = DEFAULT_BANDS.warnPct;
    breachPct = DEFAULT_BANDS.breachPct;

    /** Plain method (not a `computed`): the inputs are ngModel-bound instance fields, not signals, so a
     *  computed would memoize its first (empty) result and never re-evaluate. */
    valid(): boolean {
        return !!this.name.trim() && !!this.leftDataset && !!this.rightDataset && this.keyColumns.length > 0
            && this.warnPct >= 0 && this.breachPct >= this.warnPct;
    }

    constructor() {
        const r = this.data?.recon;
        if (r) {
            this.name = this.duplicating ? `${r.name} copy` : r.name;
            this.leftDataset = r.leftDataset;
            this.rightDataset = r.rightDataset;
            this.thirdDataset = r.thirdDataset ?? '';
            this.keyColumns = [...r.keyColumns];
            this.warnPct = r.bands?.warnPct ?? DEFAULT_BANDS.warnPct;
            this.breachPct = r.bands?.breachPct ?? DEFAULT_BANDS.breachPct;
            this.compareRows.set(r.compareColumns.map((c) => ({
                column: c.column,
                agg: c.agg ?? 'sum',
                toleranceType: c.toleranceType,
                tolerance: c.tolerance,
            })));
        }
        this.datasetsApi.list().subscribe((d) => {
            this.datasets.set(d);
            if (this.leftDataset) this.onLeftChange(this.leftDataset);
        });
    }

    onLeftChange(id: string): void {
        this.leftColumns.set(this.datasets().find((d) => d.id === id)?.columns.map((c) => c.name) ?? []);
        // drop key/compare picks no longer valid for the new left dataset
        const cols = new Set(this.leftColumns());
        this.keyColumns = this.keyColumns.filter((k) => cols.has(k));
        this.compareRows.update((rows) => rows.filter((r) => cols.has(r.column)));
    }

    addCompare(): void {
        this.compareRows.update((rows) => [...rows,
            { column: this.leftColumns()[0] ?? '', agg: 'sum', toleranceType: 'absolute', tolerance: 0 }]);
    }
    removeCompare(i: number): void {
        this.compareRows.update((rows) => rows.filter((_, idx) => idx !== i));
    }

    submit(): void {
        if (!this.valid()) return;
        this.ref.close({
            name: this.name.trim(),
            leftDataset: this.leftDataset,
            rightDataset: this.rightDataset,
            ...(this.thirdDataset ? { thirdDataset: this.thirdDataset } : {}),
            keyColumns: this.keyColumns,
            compareColumns: this.compareRows()
                .filter((r) => r.column)
                .map((r) => ({ column: r.column, agg: r.agg, toleranceType: r.toleranceType, tolerance: r.tolerance })),
            bands: { warnPct: this.warnPct, breachPct: this.breachPct },
        });
    }
}
