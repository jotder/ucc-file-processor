import { Injectable } from '@angular/core';
import { ColumnState } from 'ag-grid-community';
import { SPACE_STORAGE_KEY } from 'app/inspecto/api/spaces.service';

/**
 * Persisted per-table UI state (design `docs/superpower/ui-design-review.md` R1). Everything is
 * optional — a table persists only what its tier exposes.
 */
export interface InspectoGridState {
    /** ag-Grid column state: width / order / visibility / sort / pinned. */
    columns?: ColumnState[];
    /** Quick-filter text (restores with the search box open). */
    search?: string;
    /** Column-chooser selection (null/absent ⇒ all columns). */
    chosen?: string[] | null;
    /** Tree-table expanded node ids. */
    expanded?: string[];
}

/**
 * Best-effort `localStorage` persistence for grid layout state, keyed per pane (`stateKey`) and per
 * active space so two spaces never share layouts. Hosts opt in by setting `[stateKey]` on
 * `<inspecto-data-table>` / `<inspecto-tree-table>`; no key ⇒ every call is a no-op.
 */
@Injectable({ providedIn: 'root' })
export class GridStateService {
    // Read the persisted space id directly (not via SpacesService) so grid hosts don't pull the
    // HTTP-injecting service into every spec; the id only changes on a space switch = full reload.
    private storageKey(key: string): string {
        let space: string | null = null;
        try {
            space = localStorage.getItem(SPACE_STORAGE_KEY);
        } catch {
            // ignore — fall back to the default namespace
        }
        return `inspecto.grid.${space ?? 'default'}.${key}`;
    }

    load(key: string | undefined): InspectoGridState | null {
        if (!key) return null;
        try {
            const raw = localStorage.getItem(this.storageKey(key));
            return raw ? (JSON.parse(raw) as InspectoGridState) : null;
        } catch {
            return null;
        }
    }

    /** Merge `patch` into the stored state for `key`. Best-effort — storage errors are swallowed. */
    patch(key: string | undefined, patch: Partial<InspectoGridState>): void {
        if (!key) return;
        try {
            localStorage.setItem(
                this.storageKey(key),
                JSON.stringify({ ...(this.load(key) ?? {}), ...patch }),
            );
        } catch {
            // Quota / privacy mode — layout persistence is a convenience, never an error.
        }
    }

    clear(key: string | undefined): void {
        if (!key) return;
        try {
            localStorage.removeItem(this.storageKey(key));
        } catch {
            // ignore
        }
    }
}
