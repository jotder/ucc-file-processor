import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
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

/** Cross-field: the breach threshold can never sit below the warn threshold. */
const breachNotBelowWarn: ValidatorFn = (c: AbstractControl): ValidationErrors | null => {
    const warn = c.parent?.get('warnPct')?.value ?? 0;
    return Number(c.value) < Number(warn) ? { belowWarn: true } : null;
};

/** Define (or edit) a Dataset-vs-Dataset reconciliation — both sides, the key column(s) whose order is
 *  the Board hierarchy, the compare column(s) each with an aggregation + tolerance, and the Board's
 *  severity bands. Column pickers are driven by the left dataset's declared columns. */
@Component({
    selector: 'app-reconciliation-form-dialog',
    standalone: true,
    imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ editing ? 'Edit reconciliation' : duplicating ? 'Duplicate reconciliation' : 'New reconciliation' }}</h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="flex flex-col gap-3">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="name" placeholder="e.g. switch vs billing" cdkFocusInitial />
                    @if (form.controls.name.hasError('required')) { <mat-error>A name is required.</mat-error> }
                </mat-form-field>

                <div class="flex gap-3">
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Left dataset (anchor / source of truth)</mat-label>
                        <mat-select formControlName="leftDataset" (selectionChange)="onLeftChange($event.value)">
                            @for (d of datasets(); track d.id) { <mat-option [value]="d.id">{{ d.name }}</mat-option> }
                        </mat-select>
                        @if (form.controls.leftDataset.hasError('required')) { <mat-error>Choose a left dataset.</mat-error> }
                    </mat-form-field>
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>Right dataset</mat-label>
                        <mat-select formControlName="rightDataset">
                            @for (d of datasets(); track d.id) { <mat-option [value]="d.id">{{ d.name }}</mat-option> }
                        </mat-select>
                        @if (form.controls.rightDataset.hasError('required')) { <mat-error>Choose a right dataset.</mat-error> }
                    </mat-form-field>
                </div>

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Third dataset (optional — 3-way vs the anchor)</mat-label>
                    <mat-select formControlName="thirdDataset">
                        <mat-option [value]="''">— none (2-way) —</mat-option>
                        @for (d of datasets(); track d.id) { <mat-option [value]="d.id">{{ d.name }}</mat-option> }
                    </mat-select>
                </mat-form-field>

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Key column(s) — selection order is the Board tree</mat-label>
                    <mat-select formControlName="keyColumns" multiple>
                        @for (c of leftColumns(); track c) { <mat-option [value]="c">{{ c }}</mat-option> }
                    </mat-select>
                    @if (form.controls.keyColumns.hasError('required')) { <mat-error>Pick at least one key column.</mat-error> }
                </mat-form-field>

                <div class="flex items-center justify-between">
                    <span class="text-secondary text-xs font-semibold uppercase tracking-wider">Compare columns</span>
                    <button mat-stroked-button type="button" (click)="addCompare()" [disabled]="!leftColumns().length">
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon><span class="ml-1">Add</span>
                    </button>
                </div>
                <div [formArrayName]="'compareRows'" class="flex flex-col gap-2">
                    @for (row of compareRowsArray.controls; track $index; let i = $index) {
                        <div class="flex items-center gap-2" [formGroupName]="i">
                            <mat-form-field class="flex-1" subscriptSizing="dynamic">
                                <mat-label>Column</mat-label>
                                <mat-select formControlName="column">
                                    @for (c of leftColumns(); track c) { <mat-option [value]="c">{{ c }}</mat-option> }
                                </mat-select>
                                @if (row.get('column')?.hasError('required')) { <mat-error>Required</mat-error> }
                            </mat-form-field>
                            <mat-form-field class="w-24" subscriptSizing="dynamic">
                                <mat-label>Agg</mat-label>
                                <mat-select formControlName="agg">
                                    <mat-option value="sum">sum</mat-option>
                                    <mat-option value="count">count</mat-option>
                                </mat-select>
                            </mat-form-field>
                            <mat-form-field class="w-32" subscriptSizing="dynamic">
                                <mat-label>Tolerance</mat-label>
                                <mat-select formControlName="toleranceType">
                                    <mat-option value="exact">exact</mat-option>
                                    <mat-option value="absolute">± absolute</mat-option>
                                    <mat-option value="percent">± percent</mat-option>
                                </mat-select>
                            </mat-form-field>
                            @if (row.get('toleranceType')?.value !== 'exact') {
                                <mat-form-field class="w-24" subscriptSizing="dynamic">
                                    <mat-label>Value</mat-label>
                                    <input matInput type="number" formControlName="tolerance" />
                                </mat-form-field>
                            }
                            <button mat-icon-button type="button" (click)="removeCompare(i)" aria-label="Remove compare column">
                                <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                            </button>
                        </div>
                    }
                </div>

                <span class="text-secondary text-xs font-semibold uppercase tracking-wider">Board bands (|Δ%|)</span>
                <div class="flex items-center gap-3">
                    <mat-form-field class="w-32" subscriptSizing="dynamic">
                        <mat-label>Warn from %</mat-label>
                        <input matInput type="number" min="0" formControlName="warnPct" />
                        @if (form.controls.warnPct.hasError('min')) { <mat-error>Must be ≥ 0.</mat-error> }
                    </mat-form-field>
                    <mat-form-field class="w-32" subscriptSizing="dynamic">
                        <mat-label>Breach over %</mat-label>
                        <input matInput type="number" min="0" formControlName="breachPct" />
                        @if (form.controls.breachPct.hasError('min')) {
                            <mat-error>Must be ≥ 0.</mat-error>
                        } @else if (form.controls.breachPct.hasError('belowWarn')) {
                            <mat-error>Must be ≥ warn %.</mat-error>
                        }
                    </mat-form-field>
                    <span class="text-secondary text-xs">ok &lt; warn · warn–breach · breach &gt;</span>
                </div>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button mat-dialog-close>Cancel</button>
                <button type="submit" mat-flat-button color="primary" [disabled]="form.invalid">
                    {{ editing ? 'Save' : 'Create' }}
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class ReconciliationFormDialog {
    private fb = inject(FormBuilder);
    private datasetsApi = inject(DatasetsService);
    private ref = inject(MatDialogRef<ReconciliationFormDialog, ReconciliationFormResult>);
    private data = inject<ReconciliationFormData | null>(MAT_DIALOG_DATA, { optional: true });

    readonly duplicating = !!this.data?.recon && !!this.data?.duplicate;
    readonly editing = !!this.data?.recon && !this.data?.duplicate;
    readonly datasets = signal<Dataset[]>([]);
    readonly leftColumns = signal<string[]>([]);

    readonly form: FormGroup = this.fb.group({
        name: ['', [Validators.required]],
        leftDataset: ['', [Validators.required]],
        rightDataset: ['', [Validators.required]],
        thirdDataset: [''],
        keyColumns: [[] as string[], [Validators.required]],
        warnPct: [DEFAULT_BANDS.warnPct, [Validators.required, Validators.min(0)]],
        breachPct: [DEFAULT_BANDS.breachPct, [Validators.required, Validators.min(0), breachNotBelowWarn]],
        compareRows: this.fb.array<FormGroup>([]),
    });

    constructor() {
        this.form.controls['warnPct'].valueChanges.subscribe(() =>
            this.form.controls['breachPct'].updateValueAndValidity({ emitEvent: false }),
        );

        const r = this.data?.recon;
        if (r) {
            this.form.patchValue({
                name: this.duplicating ? `${r.name} copy` : r.name,
                leftDataset: r.leftDataset,
                rightDataset: r.rightDataset,
                thirdDataset: r.thirdDataset ?? '',
                keyColumns: [...r.keyColumns],
                warnPct: r.bands?.warnPct ?? DEFAULT_BANDS.warnPct,
                breachPct: r.bands?.breachPct ?? DEFAULT_BANDS.breachPct,
            });
            for (const c of r.compareColumns) this.pushCompareRow(c.column, c.agg ?? 'sum', c.toleranceType, c.tolerance);
        }
        this.datasetsApi.list().subscribe((d) => {
            this.datasets.set(d);
            const left = this.form.controls['leftDataset'].value;
            if (left) this.onLeftChange(left);
        });
    }

    get compareRowsArray(): FormArray<FormGroup> {
        return this.form.controls['compareRows'] as FormArray<FormGroup>;
    }

    onLeftChange(id: string): void {
        this.leftColumns.set(this.datasets().find((d) => d.id === id)?.columns.map((c) => c.name) ?? []);
        // drop key/compare picks no longer valid for the new left dataset
        const cols = new Set(this.leftColumns());
        const keyCtrl = this.form.controls['keyColumns'];
        keyCtrl.setValue((keyCtrl.value as string[]).filter((k) => cols.has(k)));
        for (let i = this.compareRowsArray.length - 1; i >= 0; i--) {
            if (!cols.has(this.compareRowsArray.at(i).get('column')?.value)) this.compareRowsArray.removeAt(i);
        }
    }

    addCompare(): void {
        this.pushCompareRow(this.leftColumns()[0] ?? '', 'sum', 'absolute', 0);
    }

    removeCompare(i: number): void {
        this.compareRowsArray.removeAt(i);
    }

    private pushCompareRow(column: string, agg: MeasureAgg, toleranceType: ToleranceType, tolerance: number): void {
        this.compareRowsArray.push(this.fb.group({
            column: [column, [Validators.required]],
            agg: [agg, [Validators.required]],
            toleranceType: [toleranceType, [Validators.required]],
            tolerance: [tolerance],
        }));
    }

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.ref.close({
            name: String(v.name).trim(),
            leftDataset: v.leftDataset,
            rightDataset: v.rightDataset,
            ...(v.thirdDataset ? { thirdDataset: v.thirdDataset } : {}),
            keyColumns: v.keyColumns,
            compareColumns: (v.compareRows as CompareColumn[])
                .filter((c) => c.column)
                .map((c) => ({ column: c.column, agg: c.agg, toleranceType: c.toleranceType, tolerance: c.tolerance })),
            bands: { warnPct: v.warnPct, breachPct: v.breachPct },
        });
    }
}
