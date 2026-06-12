import { Component, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { GammaConfigService } from '@gamma/services/config';
import { ICellRendererAngularComp } from 'ag-grid-angular';
import {
    AllCommunityModule,
    ColDef,
    colorSchemeDark,
    GridApi,
    ICellRendererParams,
    ModuleRegistry,
    Theme,
    themeQuartz,
} from 'ag-grid-community';

// One-time community-module registration for every UCC grid.
ModuleRegistry.registerModules([AllCommunityModule]);

export const UCC_GRID_LIGHT: Theme = themeQuartz;
export const UCC_GRID_DARK: Theme = themeQuartz.withPart(colorSchemeDark);

/** Shared column defaults for UCC grids. */
export const UCC_DEFAULT_COL_DEF: ColDef = { sortable: true, resizable: true };

/** Grid date-time column formatter (epoch millis or ISO string). */
export function fmtDateTime(value: unknown): string {
    if (!value) return '';
    const d = typeof value === 'number' ? new Date(value) : new Date(String(value));
    return isNaN(d.getTime()) ? String(value) : d.toLocaleString();
}

/** ag-Grid theme that follows the gamma scheme (light/dark/auto) — bind `[theme]="themeSvc.theme()"`. */
@Injectable({ providedIn: 'root' })
export class UccGridThemeService {
    readonly theme = signal<Theme>(UCC_GRID_DARK);

    constructor() {
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed())
            .subscribe((config: { scheme?: string }) => {
                const dark =
                    config?.scheme === 'dark' ||
                    (config?.scheme === 'auto' &&
                        window.matchMedia('(prefers-color-scheme: dark)').matches);
                this.theme.set(dark ? UCC_GRID_DARK : UCC_GRID_LIGHT);
            });
    }
}

/** One row action in a {@link UccActionsCell} column. */
export interface UccRowAction<T = unknown> {
    /** gamma svg icon name, e.g. 'heroicons_outline:play'. */
    icon: string | ((row: T) => string);
    hint: string | ((row: T) => string);
    visible?: (row: T) => boolean;
    disabled?: (row: T) => boolean;
    onClick: (row: T) => void;
}

/**
 * Actions cell renderer — icon buttons per row, replaces dx-data-grid's `type="buttons"` column.
 * Configure via `cellRendererParams: { actions: UccRowAction<T>[] }`.
 */
@Component({
    selector: 'app-ucc-actions-cell',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    template: `
        @for (a of shown; track $index) {
            <button
                mat-icon-button
                class="ucc-row-action"
                [matTooltip]="resolve(a.hint)"
                [disabled]="a.disabled?.(row)"
                (click)="$event.stopPropagation(); a.onClick(row)"
            >
                <mat-icon class="icon-size-5" [svgIcon]="resolve(a.icon)"></mat-icon>
            </button>
        }
    `,
})
export class UccActionsCell implements ICellRendererAngularComp {
    row!: unknown;
    shown: UccRowAction[] = [];

    agInit(params: ICellRendererParams & { actions?: UccRowAction[] }): void {
        this.row = params.data;
        this.shown = (params.actions ?? []).filter((a) => !a.visible || a.visible(this.row));
    }

    refresh(): boolean {
        return false;
    }

    resolve(v: string | ((row: unknown) => string)): string {
        return typeof v === 'function' ? v(this.row) : v;
    }
}

/**
 * Workaround: ag-grid-angular 35 + this Angular 21 shell silently skips Angular cell-renderer
 * creation on the *initial* row render (cells stay empty, no error); a forced refresh of the
 * column materializes them reliably. Bind to the grid's `(firstDataRendered)` and
 * `(rowDataUpdated)` events on every grid that uses {@link actionsColumn}.
 * TODO: re-test when bumping ag-grid / Angular and drop if fixed upstream.
 */
export function refreshActionsCells(e: { api: GridApi }): void {
    setTimeout(() => e.api.refreshCells({ force: true, columns: ['actions'] }));
}

/** Derive simple columns from the keys of loose-map rows (audit rows etc.). */
export function autoColumns(rows: Record<string, unknown>[]): ColDef[] {
    return rows.length ? Object.keys(rows[0]).map((k) => ({ field: k })) : [];
}

/** Convenience builder for an actions column. */
export function actionsColumn<T>(actions: UccRowAction<T>[], width = 160): ColDef<T> {
    return {
        colId: 'actions',
        headerName: 'Actions',
        cellRenderer: UccActionsCell,
        cellRendererParams: { actions },
        width,
        sortable: false,
        resizable: false,
    };
}
