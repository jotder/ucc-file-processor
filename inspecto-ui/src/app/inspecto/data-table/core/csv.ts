/**
 * CSV export — framework-free (no Angular). `toCsv` is a pure RFC-4180-ish serializer; `downloadCsv` does the
 * browser Blob/anchor dance. Used by the Standard+ tiers of the data-table family.
 */

/** Serialize rows × columns to a CSV string (header + body), quoting cells that need it. */
export function toCsv(rows: readonly Record<string, unknown>[], columns: readonly string[]): string {
    const esc = (v: unknown): string => {
        const s = v == null ? '' : String(v);
        return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    };
    const header = columns.map(esc).join(',');
    const body = rows.map((r) => columns.map((c) => esc(r[c])).join(',')).join('\n');
    return body ? `${header}\n${body}` : header;
}

/** Trigger a client-side CSV download (browser DOM, not Angular). */
export function downloadCsv(name: string, csv: string): void {
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name.endsWith('.csv') ? name : `${name}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
}
