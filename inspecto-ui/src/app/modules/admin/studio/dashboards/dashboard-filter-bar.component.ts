import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { FormsModule } from '@angular/forms';
import { Condition, ConditionGroup } from 'app/inspecto/query';
import { DrillEvent } from '../widgets/widget-host.component';

/**
 * Viewer-facing dashboard **filter bar** — quick filters over the dashboard's cross-filter without opening the
 * editor's condition builder. For each *exposed* field it offers the distinct sample values as a picker; every
 * active `field = value` equality condition renders as a removable chip. Both paths emit `(toggle)` with the
 * same `{field, value}` shape the tiles' drill-down uses, so the host applies one toggle rule everywhere.
 * Presentational — the host owns the ConditionGroup.
 */
@Component({
    selector: 'app-dashboard-filter-bar',
    standalone: true,
    imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (fields().length) {
            <div class="flex flex-wrap items-center gap-2" role="group" aria-label="Dashboard quick filters">
                @for (field of fields(); track field) {
                    <mat-form-field subscriptSizing="dynamic" class="w-44">
                        <mat-label>{{ field }}</mat-label>
                        <mat-select
                            [ngModel]="null"
                            (ngModelChange)="onPick(field, $event)"
                            [ngModelOptions]="{ standalone: true }"
                            [attr.aria-label]="'Filter by ' + field"
                        >
                            @for (v of valuesFor(field); track v) {
                                <mat-option [value]="v">{{ v }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                }
                @for (c of activeConditions(); track c.field + '=' + c.value) {
                    <button
                        mat-stroked-button
                        type="button"
                        class="rounded-full"
                        (click)="toggle.emit({ field: c.field, value: c.value ?? '' })"
                        [attr.aria-label]="'Remove filter ' + c.field + ' = ' + c.value"
                    >
                        <span class="font-medium">{{ c.field }}</span>
                        <span class="text-secondary px-1">=</span>
                        <span>{{ c.value }}</span>
                        <mat-icon class="icon-size-4 ml-1" svgIcon="heroicons_outline:x-mark"></mat-icon>
                    </button>
                }
            </div>
        }
    `,
})
export class DashboardFilterBarComponent {
    /** Fields the dashboard exposes as quick filters (a subset of the cross-filter's columns). */
    readonly fields = input.required<string[]>();
    /** Distinct candidate values per field (derived from the tiles' sample rows by the host). */
    readonly values = input.required<Record<string, string[]>>();
    /** The dashboard's live cross-filter — equality conditions on exposed fields render as chips. */
    readonly filter = input.required<ConditionGroup>();
    /** Toggle `field = value` in the cross-filter (add when absent, remove when present). */
    readonly toggle = output<DrillEvent>();

    /** Top-level `field = value` conditions on exposed fields — the removable chips. */
    readonly activeConditions = computed<Condition[]>(() => {
        const exposed = new Set(this.fields());
        return this.filter().items.filter(
            (i): i is Condition => i.kind === 'condition' && i.operator === '=' && exposed.has(i.field),
        );
    });

    valuesFor(field: string): string[] {
        return this.values()[field] ?? [];
    }

    onPick(field: string, value: string | null): void {
        if (value != null && value !== '') this.toggle.emit({ field, value });
    }
}
