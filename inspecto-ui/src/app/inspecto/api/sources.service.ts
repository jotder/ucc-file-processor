import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/**
 * A configured acquisition source (GET /sources) — one `source.*` block of a pipeline config: the
 * connector + connection profile it pulls from, its include/exclude globs, dedup mode, delivery
 * guarantee, incremental watermark and fetch tuning. Watermark fields: `incrementalWatermark` is the
 * configured strategy (e.g. `last_modified`), `dbWatermarkCurrent` the last recorded slice (DB sources).
 */
export interface SourceView {
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

/** Configured acquisition sources (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class SourcesService {
    private http = inject(HttpClient);

    /** Every configured source across all pipelines. */
    list(): Observable<SourceView[]> {
        return this.http.get<SourceView[]>(apiUrl('/sources'));
    }
}
