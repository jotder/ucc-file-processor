import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { CHART_PALETTES } from 'app/inspecto/theme/chart-tokens';
import { WidgetOptions } from './widget-types';

/** The advanced (cog) dialog's input/output — the widget's current options, closed with the edited options
 *  (or undefined on cancel). A closed, curated set of knobs — no free-form styling. */
export type WidgetOptionsData = WidgetOptions;

const PALETTE_KEYS = Object.keys(CHART_PALETTES);
const LEGEND_POSITIONS = ['top', 'right', 'bottom', 'left'] as const;

@Component({
    selector: 'app-widget-options-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Advanced options</h2>
        <mat-dialog-content class="flex flex-col gap-3">
            <form [formGroup]="form" class="flex flex-col gap-3">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Title</mat-label>
                    <input matInput formControlName="title" placeholder="Defaults to the widget's name" />
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Subtitle</mat-label>
                    <input matInput formControlName="subtitle" />
                </mat-form-field>
                <div class="grid grid-cols-2 gap-3">
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>X axis title</mat-label>
                        <input matInput formControlName="xTitle" />
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Y axis title</mat-label>
                        <input matInput formControlName="yTitle" />
                    </mat-form-field>
                </div>
                <div class="grid grid-cols-2 gap-3">
                    <mat-checkbox formControlName="legendShow">Show legend</mat-checkbox>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Legend position</mat-label>
                        <mat-select formControlName="legendPosition">
                            @for (p of legendPositions; track p) { <mat-option [value]="p">{{ p }}</mat-option> }
                        </mat-select>
                    </mat-form-field>
                </div>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Color palette</mat-label>
                    <mat-select formControlName="palette">
                        @for (p of paletteKeys; track p) { <mat-option [value]="p">{{ p }}</mat-option> }
                    </mat-select>
                </mat-form-field>
                <div class="grid grid-cols-2 gap-3">
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Sort</mat-label>
                        <mat-select formControlName="sort">
                            <mat-option [value]="null">Unsorted</mat-option>
                            <mat-option value="asc">Ascending</mat-option>
                            <mat-option value="desc">Descending</mat-option>
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Limit (top N)</mat-label>
                        <input matInput type="number" min="1" formControlName="limit" />
                    </mat-form-field>
                </div>
                <mat-checkbox formControlName="stacked">Stack series</mat-checkbox>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class WidgetOptionsDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<WidgetOptionsDialog, WidgetOptions>);
    readonly data = inject<WidgetOptionsData>(MAT_DIALOG_DATA);

    readonly paletteKeys = PALETTE_KEYS;
    readonly legendPositions = LEGEND_POSITIONS;

    readonly form = this.fb.group({
        title: [this.data.title ?? ''],
        subtitle: [this.data.subtitle ?? ''],
        xTitle: [this.data.axis?.xTitle ?? ''],
        yTitle: [this.data.axis?.yTitle ?? ''],
        legendShow: [this.data.legend?.show ?? true],
        legendPosition: [this.data.legend?.position ?? 'top'],
        palette: [this.data.palette ?? PALETTE_KEYS[0]],
        sort: [this.data.sort ?? null],
        limit: [this.data.limit ?? null],
        stacked: [this.data.stacked ?? false],
    });

    save(): void {
        const v = this.form.value;
        const options: WidgetOptions = {
            title: v.title?.trim() || undefined,
            subtitle: v.subtitle?.trim() || undefined,
            axis: v.xTitle?.trim() || v.yTitle?.trim() ? { xTitle: v.xTitle?.trim() || undefined, yTitle: v.yTitle?.trim() || undefined } : undefined,
            legend: { show: v.legendShow ?? true, position: v.legendPosition ?? 'top' },
            palette: v.palette ?? undefined,
            sort: v.sort ?? undefined,
            limit: v.limit ?? undefined,
            stacked: v.stacked ?? false,
        };
        this.ref.close(options);
    }
}
