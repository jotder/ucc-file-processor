import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { Aggregation, ChannelValue, ControlSpec, ControlValues, TimeGrain, VizField, VizPlugin } from 'app/inspecto/viz';

const AGGS: Aggregation[] = ['sum', 'avg', 'min', 'max', 'count', 'countDistinct'];
const GRAINS: TimeGrain[] = ['auto', 'day', 'week', 'month'];

/**
 * Field mapper — the explore workbench's presentational control panel (a **controlled** component: renders
 * from `values`, emits `valuesChange`; the host owns the state). One row per plugin {@link ControlSpec}: a
 * field picker (filtered to the channel's accepted roles) plus, for single measure channels, an aggregation
 * picker. No services. Mirrors the parser/dataset taggers.
 */
@Component({
    selector: 'app-explore-controls',
    standalone: true,
    imports: [FormsModule, MatFormFieldModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="space-y-3">
            @for (control of plugin().controls; track control.channel) {
                <div class="flex items-end gap-2">
                    <mat-form-field class="flex-1" subscriptSizing="dynamic">
                        <mat-label>{{ control.label }}</mat-label>
                        @if (control.multiple) {
                            <mat-select
                                multiple
                                [ngModel]="selectedFields(control.channel)"
                                (ngModelChange)="onFields(control, $event)"
                                [aria-label]="control.label"
                            >
                                @for (f of fieldsFor(control); track f.name) {
                                    <mat-option [value]="f.name">{{ f.label || f.name }}</mat-option>
                                }
                            </mat-select>
                        } @else {
                            <mat-select
                                [ngModel]="selectedField(control.channel)"
                                (ngModelChange)="onField(control, $event)"
                                [aria-label]="control.label"
                            >
                                <mat-option [value]="null">—</mat-option>
                                @for (f of fieldsFor(control); track f.name) {
                                    <mat-option [value]="f.name">{{ f.label || f.name }}</mat-option>
                                }
                            </mat-select>
                        }
                    </mat-form-field>

                    @if (isTemporalSelected(control)) {
                        <mat-form-field class="w-32" subscriptSizing="dynamic">
                            <mat-label>Time grain</mat-label>
                            <mat-select
                                [ngModel]="grainFor(control.channel)"
                                (ngModelChange)="onGrain(control.channel, $event)"
                                [aria-label]="control.label + ' time grain'"
                            >
                                @for (g of grains; track g) { <mat-option [value]="g">{{ g }}</mat-option> }
                            </mat-select>
                        </mat-form-field>
                    }

                    @if (control.isMeasure && !control.multiple && !isExpressionSelected(control.channel)) {
                        <mat-form-field class="w-32" subscriptSizing="dynamic">
                            <mat-label>Aggregation</mat-label>
                            <mat-select
                                [ngModel]="aggFor(control.channel)"
                                (ngModelChange)="onAgg(control.channel, $event)"
                                [aria-label]="control.label + ' aggregation'"
                            >
                                @for (a of aggs; track a) { <mat-option [value]="a">{{ a }}</mat-option> }
                            </mat-select>
                        </mat-form-field>
                    }
                </div>
            }
        </div>
    `,
})
export class ExploreControlsComponent {
    readonly aggs = AGGS;
    readonly grains = GRAINS;

    readonly plugin = input.required<VizPlugin>();
    readonly fields = input.required<VizField[]>();
    readonly values = input<ControlValues>({});
    readonly valuesChange = output<ControlValues>();

    fieldsFor(control: ControlSpec): VizField[] {
        return this.fields().filter((f) => control.acceptRoles.includes(f.role));
    }

    selectedField(channel: ControlSpec['channel']): string | null {
        return this.values()[channel]?.[0]?.field ?? null;
    }
    selectedFields(channel: ControlSpec['channel']): string[] {
        return (this.values()[channel] ?? []).map((v) => v.field);
    }
    aggFor(channel: ControlSpec['channel']): Aggregation {
        return this.values()[channel]?.[0]?.agg ?? 'sum';
    }

    /** The x-style grain picker shows only when the channel's selected field is temporal. */
    isTemporalSelected(control: ControlSpec): boolean {
        if (control.multiple || control.isMeasure) return false;
        const name = this.selectedField(control.channel);
        return !!name && this.fields().find((f) => f.name === name)?.role === 'temporal';
    }
    grainFor(channel: ControlSpec['channel']): TimeGrain {
        return this.values()[channel]?.[0]?.grain ?? 'auto';
    }
    onGrain(channel: ControlSpec['channel'], grain: TimeGrain): void {
        const cur = this.values()[channel]?.[0];
        if (!cur) return;
        this.patch(channel, [{ ...cur, grain }]);
    }

    onField(control: ControlSpec, field: string | null): void {
        const cv: ChannelValue[] = field ? [this.toChannelValue(control, field)] : [];
        this.patch(control.channel, cv);
    }
    onFields(control: ControlSpec, fields: string[]): void {
        this.patch(control.channel, fields.map((field) => this.toChannelValue(control, field)));
    }

    /** A named-measure field carries its expression (no aggregation applies); a column gets the default agg. */
    private toChannelValue(control: ControlSpec, field: string): ChannelValue {
        const expression = this.fields().find((f) => f.name === field)?.expression;
        if (expression) return { field, expression };
        return { field, agg: control.isMeasure ? this.aggFor(control.channel) : undefined };
    }

    /** Hide the aggregation picker for named measures — the expression already aggregates. */
    isExpressionSelected(channel: ControlSpec['channel']): boolean {
        return !!this.values()[channel]?.[0]?.expression;
    }
    onAgg(channel: ControlSpec['channel'], agg: Aggregation): void {
        const cur = this.values()[channel]?.[0];
        if (!cur) return;
        this.patch(channel, [{ ...cur, agg }]);
    }

    private patch(channel: ControlSpec['channel'], cv: ChannelValue[]): void {
        this.valuesChange.emit({ ...this.values(), [channel]: cv });
    }
}
