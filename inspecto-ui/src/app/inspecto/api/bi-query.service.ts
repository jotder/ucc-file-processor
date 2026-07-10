import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One validated measure on the wire — an agg name + column identifier, never SQL text. */
export interface BiMeasure {
    agg: string;
    field?: string;
}

/** One flat filter term (implicit AND). `op` ∈ `= != > >= < <= in like isNull notNull`. */
export interface BiFilter {
    field: string;
    op: string;
    value?: unknown;
}

/** The `POST /bi/query` body (BI-7) — `dataset` plus at least one of `measures`/`groupBy`. */
export interface BiQueryBody {
    dataset: string;
    measures?: BiMeasure[];
    groupBy?: string[];
    filters?: BiFilter[];
    orderBy?: { field: string; dir: 'asc' | 'desc' }[];
    limit?: number;
}

/** The Result Set contract `/bi/query` shares with `/queries/{id}/run`. */
export interface BiQueryResult {
    resultSet: { columns: { name: string; type: string; role?: string }[]; rowCount: number };
    rows: Record<string, unknown>[];
    statistics: { rowCount: number; elapsedMs: number; truncated: boolean };
    sql: string;
}

/**
 * Headless BI query client (BI-7) — the server twin of the offline `runSpec` seam: spec-based
 * measures/dimensions/filters compiled and executed server-side over the dataset's trusted relation
 * (calculated columns + shared refs included). Fail-closed: 503 no write root, 404 unknown dataset,
 * 422 bad spec. Consumed by `DatasetResultService`'s M2 path; offline/mock mode never calls it.
 */
@Injectable({ providedIn: 'root' })
export class BiQueryService {
    private http = inject(HttpClient);

    run(body: BiQueryBody): Observable<BiQueryResult> {
        return this.http.post<BiQueryResult>(apiUrl('/bi/query'), body);
    }
}
