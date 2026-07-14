import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/**
 * A configured acquisition collector (GET /collectors) — one `collector.*` block of a pipeline config:
 * the connector + connection profile it pulls from, its include/exclude globs, dedup mode, delivery
 * guarantee, incremental watermark and fetch tuning. Watermark fields: `incrementalWatermark` is the
 * configured strategy (e.g. `last_modified`), `dbWatermarkCurrent` the last recorded slice (DB collectors).
 */
export interface CollectorView {
    pipeline: string;
    id: string;
    connector: string;
    connection: string | null;
    includes: string[];
    excludes: string[];
    recursiveDepth: number;
    duplicateMode: string;
    duplicateOnChange: string;
    guarantee: string;
    incrementalWatermark: string | null;
    fetchParallel: number;
    fetchRateLimit: number;
    postAction: string;
    dbWatermarkCurrent: string | null;
}

/** Configured acquisition collectors (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class CollectorsService {
    private http = inject(HttpClient);

    /** Every configured collector across all pipelines. */
    list(): Observable<CollectorView[]> {
        return this.http.get<CollectorView[]>(apiUrl('/collectors'));
    }

    /** Ask a collector to re-scan its origin now (POST /collectors/{id}/notify). */
    notify(id: string): Observable<unknown> {
        return this.http.post(apiUrl(`/collectors/${encodeURIComponent(id)}/notify`), {});
    }
}
