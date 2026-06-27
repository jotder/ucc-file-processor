/**
 * Offline SQL execution for the Pro data-table editor.
 *
 * Lazy-loads **AlaSQL** via a dynamic `import()` so the (sizeable) engine stays out of the main bundle and
 * only downloads the first time someone actually runs a query. The query runs in-browser over the rows the
 * host already holds — no backend round-trip.
 *
 * The editor shows DuckDB-style **double-quoted** identifiers (`"col"`); AlaSQL wants **backtick**
 * identifiers, so {@link toAlaSqlDialect} rewrites them for the run path only — single-quoted string
 * literals are left untouched. A genuinely-broken query throws; we surface that as an error so the caller
 * can decline to render it (and skip adding it to history).
 */
export interface SqlRunResult {
    ok: boolean;
    rows: Record<string, unknown>[];
    /** Present (and `ok` false) when the query failed to parse/execute. */
    error?: string;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
let enginePromise: Promise<any> | null = null;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function engine(): Promise<any> {
    if (!enginePromise) enginePromise = import('alasql').then((m) => (m as { default?: unknown }).default ?? m);
    return enginePromise;
}

/** Rewrite `"ident"` → `` `ident` `` (DuckDB → AlaSQL identifier quoting), preserving `'string'` literals. */
export function toAlaSqlDialect(sql: string): string {
    return sql.replace(/"((?:[^"]|"")*)"/g, (_, id: string) => '`' + id.replace(/""/g, '"') + '`');
}

/** Run `sql` over `rows` (registered as the table `source`). Resolves to matched rows or an error. */
export async function runSql(sql: string, source: string, rows: Record<string, unknown>[]): Promise<SqlRunResult> {
    const trimmed = sql.trim();
    if (!trimmed) return { ok: false, rows: [], error: 'Enter a SQL query to run.' };

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    let alasql: any;
    try {
        alasql = await engine();
    } catch {
        return { ok: false, rows: [], error: 'The SQL engine failed to load.' };
    }

    try {
        const db = new alasql.Database();
        const table = (source || 'data').replace(/[^A-Za-z0-9_]/g, '_') || 'data';
        db.exec('CREATE TABLE `' + table + '`');
        db.tables[table].data = rows;
        const result = db.exec(toAlaSqlDialect(trimmed));
        if (!Array.isArray(result)) {
            return { ok: false, rows: [], error: 'Only SELECT queries can run in the offline preview.' };
        }
        return { ok: true, rows: result as Record<string, unknown>[] };
    } catch (e) {
        return { ok: false, rows: [], error: e instanceof Error ? e.message : String(e) };
    }
}
