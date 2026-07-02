import { ChangeDetectionStrategy, Component, ViewChild, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { WidgetOptions } from './widget-types';
import { WIDGET_OPTION_ATTRIBUTES } from './widget-option-attributes';

/** The advanced (cog) dialog's input/output — the widget's current options, closed with the edited options
 *  (or undefined on cancel). A closed, curated set of knobs — no free-form styling. */
export type WidgetOptionsData = WidgetOptions;

/**
 * Advanced widget options (the cog dialog) — spec-driven via `<inspecto-schema-form>`
 * ({@link WIDGET_OPTION_ATTRIBUTES}). Every knob is always visible but optional; the flat form values are
 * re-nested into `axis`/`legend` on save.
 */
@Component({
    selector: 'app-widget-options-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Advanced options</h2>
        <mat-dialog-content>
            <inspecto-schema-form #sf [specs]="attributes" [initial]="initialValue"></inspecto-schema-form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class WidgetOptionsDialog {
    private ref = inject(MatDialogRef<WidgetOptionsDialog, WidgetOptions>);
    readonly data = inject<WidgetOptionsData>(MAT_DIALOG_DATA);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;
    readonly attributes = WIDGET_OPTION_ATTRIBUTES;

    /** Current options mapped onto the flat attribute keys (axis/legend are flattened). */
    readonly initialValue: Record<string, unknown> = {
        title: this.data.title ?? '',
        subtitle: this.data.subtitle ?? '',
        xTitle: this.data.axis?.xTitle ?? '',
        yTitle: this.data.axis?.yTitle ?? '',
        legendShow: this.data.legend?.show ?? true,
        legendPosition: this.data.legend?.position ?? 'top',
        palette: this.data.palette ?? undefined,
        sort: this.data.sort ?? '',
        limit: this.data.limit ?? null,
        stacked: this.data.stacked ?? false,
    };

    save(): void {
        const v = this.schemaForm.value();
        const str = (x: unknown) => (typeof x === 'string' ? x.trim() : '');
        const xTitle = str(v['xTitle']);
        const yTitle = str(v['yTitle']);
        const options: WidgetOptions = {
            title: str(v['title']) || undefined,
            subtitle: str(v['subtitle']) || undefined,
            axis: xTitle || yTitle ? { xTitle: xTitle || undefined, yTitle: yTitle || undefined } : undefined,
            legend: {
                show: (v['legendShow'] as boolean) ?? true,
                position: (v['legendPosition'] as 'top' | 'right' | 'bottom' | 'left') ?? 'top',
            },
            palette: (v['palette'] as string) || undefined,
            sort: (str(v['sort']) || undefined) as WidgetOptions['sort'],
            limit: (v['limit'] as number) ?? undefined,
            stacked: (v['stacked'] as boolean) ?? false,
        };
        this.ref.close(options);
    }
}
