import { fieldNames } from './column-resolve';
import { toCsv } from './csv';
import { quickFilterRows } from './quick-filter';

/**
 * Framework-free state + derivations for a data table: holds the rows, the search text and (optional) explicit
 * column names, and derives the displayed (filtered) rows and a CSV string. No Angular — the
 * `DataTableComponent` is a thin shell that pushes its signal values in and reads these back, so the reusable
 * logic lives here (and is unit-testable in isolation). Setters are chainable for terse call sites.
 */
export class DataTableController {
    rows: Record<string, unknown>[] = [];
    search = '';
    private explicit?: string[];

    constructor(opts?: { columns?: string[] }) {
        this.explicit = opts?.columns;
    }

    setRows(rows: readonly Record<string, unknown>[]): this {
        this.rows = rows as Record<string, unknown>[];
        return this;
    }
    setSearch(text: string): this {
        this.search = text ?? '';
        return this;
    }
    setColumns(columns?: string[]): this {
        this.explicit = columns;
        return this;
    }

    /** Column names — explicit if given, else inferred from the first row. */
    columns(): string[] {
        return fieldNames(this.rows, this.explicit);
    }

    /** Rows after applying the quick-filter search across the resolved columns. */
    displayedRows(): Record<string, unknown>[] {
        return quickFilterRows(this.rows, this.search, this.columns());
    }

    /** The displayed rows as a CSV string. */
    csv(): string {
        return toCsv(this.displayedRows(), this.columns());
    }
}
