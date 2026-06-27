import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * Icon-button + menu to choose which columns are shown. In **standard** it toggles grid column visibility;
 * in **pro** the host treats the selection as the SQL `SELECT` projection. Emits the full selected set on
 * every change.
 */
@Component({
    selector: 'inspecto-column-chooser',
    standalone: true,
    imports: [MatButtonModule, MatCheckboxModule, MatIconModule, MatMenuModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <button mat-icon-button type="button" [matMenuTriggerFor]="menu" [matTooltip]="label()" [attr.aria-label]="label()">
            <mat-icon class="icon-size-5" svgIcon="heroicons_outline:view-columns"></mat-icon>
        </button>
        <mat-menu #menu="matMenu">
            <div class="min-w-56 px-2 py-1" (click)="$event.stopPropagation()">
                <div class="mb-1 flex items-center gap-1 border-b pb-1" style="border-color: var(--gamma-border)">
                    <button mat-button type="button" (click)="selectedChange.emit(columns())">All</button>
                    <button mat-button type="button" (click)="selectedChange.emit([])">None</button>
                </div>
                @for (c of columns(); track c) {
                    <mat-checkbox class="block" [checked]="chosen().has(c)" (change)="toggle(c, $event.checked)">
                        {{ c }}
                    </mat-checkbox>
                }
            </div>
        </mat-menu>
    `,
})
export class ColumnChooserComponent {
    readonly columns = input<string[]>([]);
    readonly selected = input<string[]>([]);
    readonly label = input('Choose columns');
    readonly selectedChange = output<string[]>();

    protected readonly chosen = computed(() => new Set(this.selected()));

    toggle(col: string, on: boolean): void {
        const next = new Set(this.selected());
        if (on) next.add(col);
        else next.delete(col);
        // Preserve the source column order.
        this.selectedChange.emit(this.columns().filter((c) => next.has(c)));
    }
}
