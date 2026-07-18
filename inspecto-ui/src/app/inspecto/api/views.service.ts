import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** A `sink.view` node's projected identity (GET /views) — a non-persistent logical store. */
export interface PipelineViewSummary {
    store: string;
    flow: string;
    source_store: string[];
    has_derived_sql: boolean;
    defined_at: string;
}

/** The full view definition (GET /views/{name}) — adds the captured `derived_sql`, when concretised. */
export interface PipelineViewDefinition extends PipelineViewSummary {
    derived_sql?: string;
}

/** Bounded rows from running a view's `derived_sql` (GET /views/{name}/data?limit=N). */
export interface PipelineViewData {
    view: string;
    columns: string[];
    rowCount: number;
    capped: boolean;
    rows: Record<string, unknown>[];
}

/**
 * Read-only consumer for `sink.view` pipeline nodes (T32 follow-up): the backend runs a view's captured
 * `derived_sql` over a resource-capped sandbox and returns bounded rows — there is no file/table to
 * browse via {@link DbBrowserService}. 409 (no `derived_sql` yet — the flow hasn't run) and 422 (query
 * failed) surface through {@code apiErrorMessage} at the call site.
 */
@Injectable({ providedIn: 'root' })
export class ViewsService {
    private http = inject(HttpClient);

    list(): Observable<PipelineViewSummary[]> {
        return this.http.get<PipelineViewSummary[]>(apiUrl('/views'));
    }

    get(name: string): Observable<PipelineViewDefinition> {
        return this.http.get<PipelineViewDefinition>(apiUrl(`/views/${encodeURIComponent(name)}`));
    }

    data(name: string, limit?: number): Observable<PipelineViewData> {
        return this.http.get<PipelineViewData>(apiUrl(`/views/${encodeURIComponent(name)}/data`), {
            params: toParams(limit != null ? { limit } : {}),
        });
    }
}
