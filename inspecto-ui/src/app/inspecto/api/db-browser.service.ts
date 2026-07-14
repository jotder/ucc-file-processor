import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** One browsable table within a group (a Parquet/CSV store, or — Phase 2 — an operational DB table). */
export interface DbTable {
    name: string;
    format?: string;          // 'PARQUET' | 'CSV' for business stores
    dataset?: string | null;  // owning dataset id, when a dataset component references this store
}

/** A catalog group: the business-data "stores" group, or an operational capability group. */
export interface DbGroup {
    id: string;
    label: string;
    kind: string;             // 'parquet' | 'operational'
    engine?: string;          // 'duckdb' | 'postgres' (operational only)
    live?: boolean;           // operational: whether the capability runs on a DB backend
    tables: DbTable[];
}

export interface DbCatalog { groups: DbGroup[]; }

export interface DbColumn { name: string; type: string; role?: string; cardinality?: number; }

export interface DbResult {
    columns: DbColumn[];
    rows: Record<string, unknown>[];
    statistics: { rowCount: number; elapsedMs: number; truncated: boolean };
}

/** Paginated / sorted browse of one table. */
export interface DbTableQuery {
    name: string;
    group?: string;
    limit?: number;
    offset?: number;
    sort?: string;            // 'field:asc' | 'field:desc'
}

/** Ad-hoc read-only SQL scoped to one group/table (the SQL references the table by name). */
export interface DbAdHocQuery {
    table: string;
    sql: string;
    group?: string;
    limit?: number;
    offset?: number;
}

/**
 * The raw table browser API (design {@code docs/superpower/db-browser-design.md}): list a space's
 * browsable stores, page through a table's rows, and run guarded read-only SQL. Space-agnostic — the
 * global {@code spaceInterceptor} scopes {@code /db/*} to the active space.
 */
@Injectable({ providedIn: 'root' })
export class DbBrowserService {
    private http = inject(HttpClient);

    catalog(): Observable<DbCatalog> {
        return this.http.get<DbCatalog>(apiUrl('/db/catalog'));
    }

    table(q: DbTableQuery): Observable<DbResult> {
        return this.http.get<DbResult>(apiUrl('/db/table'), { params: toParams({ ...q }) });
    }

    query(body: DbAdHocQuery): Observable<DbResult> {
        return this.http.post<DbResult>(apiUrl('/db/query'), body);
    }
}
