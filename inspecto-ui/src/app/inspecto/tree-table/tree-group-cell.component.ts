import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ICellRendererAngularComp } from 'ag-grid-angular';
import { ICellRendererParams } from 'ag-grid-community';
import { FlatTreeRow } from './tree-types';

type TreeCellParams = ICellRendererParams<FlatTreeRow> & { toggle?: (id: string) => void };

/**
 * The tree (first) column's cell: a depth-indented label with an expand/collapse chevron for parent rows
 * and an optional leading data-type/status icon. The only interactive/icon cell renderer pattern that
 * works on Community ag-Grid — an Angular component renderer (mirrors {@code InspectoActionsCell}). Expand
 * toggles are delegated to the host via the injected {@code toggle} param, keeping expand state parent-owned.
 */
@Component({
    selector: 'inspecto-tree-group-cell',
    standalone: true,
    imports: [MatButtonModule, MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex items-center" [style.padding-left.rem]="indent">
            @if (row?.__hasChildren) {
                <button
                    mat-icon-button
                    type="button"
                    class="!h-6 !w-6 shrink-0"
                    (click)="onToggle($event)"
                    [attr.aria-label]="row?.__expanded ? 'Collapse row' : 'Expand row'"
                    [attr.aria-expanded]="row?.__expanded">
                    <mat-icon
                        class="icon-size-4"
                        [svgIcon]="row?.__expanded ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"></mat-icon>
                </button>
            } @else {
                <span class="inline-block shrink-0" style="width: 1.5rem"></span>
            }
            @if (row?.__icon) {
                <mat-icon class="icon-size-4 mr-1 shrink-0 opacity-70" [svgIcon]="row!.__icon!"></mat-icon>
            }
            <span class="truncate">{{ row?.__label }}</span>
        </div>
    `,
})
export class TreeGroupCell implements ICellRendererAngularComp {
    row: FlatTreeRow | undefined;
    indent = 0.25;
    private toggleFn?: (id: string) => void;

    agInit(params: TreeCellParams): void {
        this.apply(params);
    }

    /** Recreate rather than in-place refresh (matches InspectoActionsCell) — flatten emits fresh rows. */
    refresh(): boolean {
        return false;
    }

    onToggle(e: Event): void {
        e.stopPropagation(); // don't also fire rowClick
        if (this.row) this.toggleFn?.(this.row.__id);
    }

    private apply(params: TreeCellParams): void {
        this.row = params.data;
        this.indent = 0.25 + (this.row?.__depth ?? 0) * 1.1;
        this.toggleFn = params.toggle;
    }
}
