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

// One-time community-module registration for every Inspecto grid.
ModuleRegistry.registerModules([AllCommunityModule]);

/**
 * Quartz params wired to the gamma/Fuse design tokens (the `--gamma-*` custom properties the template
 * sets on `body.light`/`.dark`), so every Inspecto grid matches the app's surface, border, accent and
 * typography instead of ag-Grid's stock palette/font. The vars themselves switch with the scheme, so the
 * same params serve both the light and dark theme variants below.
 */
const GAMMA_GRID_PARAMS = {
    fontFamily: 'inherit',
    fontSize: '13px',
    headerFontSize: '12px',
    headerFontWeight: 600,
    foregroundColor: 'var(--gamma-text-default)',
    backgroundColor: 'var(--gamma-bg-card)',
    borderColor: 'var(--gamma-border)',
    chromeBackgroundColor: 'var(--gamma-bg-default)',
    headerBackgroundColor: 'var(--gamma-bg-default)',
    headerTextColor: 'var(--gamma-text-secondary)',
    accentColor: 'var(--gamma-primary)',
    rowHoverColor: 'var(--gamma-bg-hover)',
    selectedRowBackgroundColor: 'rgba(var(--gamma-primary-rgb), 0.12)',
    oddRowBackgroundColor: 'transparent',
    wrapperBorderRadius: '12px',
    borderRadius: '6px',
} as const;

export const INSPECTO_GRID_LIGHT: Theme = themeQuartz.withParams(GAMMA_GRID_PARAMS);
export const INSPECTO_GRID_DARK: Theme =
    themeQuartz.withPart(colorSchemeDark).withParams(GAMMA_GRID_PARAMS);

/** Shared column defaults for Inspecto grids. */
export const INSPECTO_DEFAULT_COL_DEF: ColDef = { sortable: true, resizable: true };

/**
 * Build an ag-Grid "no rows" overlay so an empty grid reads as an intentional empty state
 * instead of a blank panel. Bind via `[overlayNoRowsTemplate]` on grids that don't already
 * swap in `<inspecto-empty-state>`. Colors inherit the gamma `--gamma-*` scheme tokens, and
 * the message is HTML-escaped to keep callers from injecting markup.
 */
export function noRowsOverlay(title = 'No data to display', hint?: string): string {
    const esc = (s: string) =>
        s.replace(/[&<>"']/g, (c) =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
        );
    const hintHtml = hint
        ? `<span style="opacity:.75;max-width:32rem;">${esc(hint)}</span>`
        : '';
    return (
        `<div role="status" style="display:flex;flex-direction:column;align-items:center;gap:6px;` +
        `padding:32px 24px;text-align:center;color:var(--gamma-text-secondary);font-size:13px;line-height:1.5;">` +
        `<span style="font-weight:600;color:var(--gamma-text-default);">${esc(title)}</span>${hintHtml}</div>`
    );
}

/** Grid date-time column formatter — now lives in the shared `inspecto/format` lib; re-exported here so the
 *  many `from 'app/inspecto/grid'` importers (grids' `valueFormatter`s) stay unchanged. */
export { fmtDateTime } from 'app/inspecto/format';

/** ag-Grid theme that follows the gamma scheme (light/dark/auto) — bind `[theme]="themeSvc.theme()"`. */
@Injectable({ providedIn: 'root' })
export class InspectoGridThemeService {
    readonly theme = signal<Theme>(INSPECTO_GRID_DARK);

    constructor() {
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed())
            .subscribe((config: { scheme?: string }) => {
                const dark =
                    config?.scheme === 'dark' ||
                    (config?.scheme === 'auto' &&
                        window.matchMedia('(prefers-color-scheme: dark)').matches);
                this.theme.set(dark ? INSPECTO_GRID_DARK : INSPECTO_GRID_LIGHT);
            });
    }
}

/** One row action in a {@link InspectoActionsCell} column. */
export interface InspectoRowAction<T = unknown> {
    /** gamma svg icon name, e.g. 'heroicons_outline:play'. */
    icon: string | ((row: T) => string);
    hint: string | ((row: T) => string);
    visible?: (row: T) => boolean;
    disabled?: (row: T) => boolean;
    onClick: (row: T) => void;
}

/**
 * Actions cell renderer — icon buttons per row, replaces dx-data-grid's `type="buttons"` column.
 * Configure via `cellRendererParams: { actions: InspectoRowAction<T>[] }`.
 */
@Component({
    selector: 'inspecto-actions-cell',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    template: `
        @for (a of shown; track $index) {
            <button
                mat-icon-button
                class="inspecto-row-action"
                [matTooltip]="resolve(a.hint)"
                [attr.aria-label]="resolve(a.hint)"
                [disabled]="a.disabled?.(row)"
                (click)="$event.stopPropagation(); a.onClick(row)"
            >
                <mat-icon class="icon-size-5" [svgIcon]="resolve(a.icon)"></mat-icon>
            </button>
        }
    `,
})
export class InspectoActionsCell implements ICellRendererAngularComp {
    row!: unknown;
    shown: InspectoRowAction[] = [];

    agInit(params: ICellRendererParams & { actions?: InspectoRowAction[] }): void {
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
    setTimeout(() => {
        if (e.api.isDestroyed()) return;
        e.api.refreshCells({ force: true, columns: ['actions'] });
    });
}

/** Derive simple columns from the keys of loose-map rows (audit rows etc.). */
export function autoColumns(rows: Record<string, unknown>[]): ColDef[] {
    return rows.length ? Object.keys(rows[0]).map((k) => ({ field: k })) : [];
}

/** Convenience builder for an actions column. */
export function actionsColumn<T>(actions: InspectoRowAction<T>[], width = 160): ColDef<T> {
    return {
        colId: 'actions',
        headerName: 'Actions',
        cellRenderer: InspectoActionsCell,
        cellRendererParams: { actions },
        width,
        sortable: false,
        resizable: false,
    };
}
