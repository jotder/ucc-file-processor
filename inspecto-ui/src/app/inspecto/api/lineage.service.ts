import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** One ingest row that fed a store: how many rows of {@link inputFile} landed in {@link partition}. */
export interface UpstreamRow {
    pipeline: string;
    batchId: string;
    inputFile: string;
    partition: string;
    rowCount: number;
}

/** An authored flow that reads a store as a source, with the sink stores it produces. */
export interface DownstreamFlow {
    flow: string;
    sinks: string[];
}

/** Cross-engine lineage around one store: files in (ingest) + flows out (authored). */
export interface StoreLineage {
    store: string;
    upstream: UpstreamRow[];
    downstream: DownstreamFlow[];
}

/**
 * Store-keyed cross-engine lineage (`GET /lineage?store=`). The store bridges the two provenance halves:
 * ingest records file→store/partition counts; an authored flow reads the store and emits step counts. See
 * `docs/GLOSSARY.md` §11 — the bridge is the store, never a shared batch id.
 */
@Injectable({ providedIn: 'root' })
export class LineageService {
    private http = inject(HttpClient);

    lineage(store: string): Observable<StoreLineage> {
        return this.http.get<StoreLineage>(apiUrl('/lineage'), { params: toParams({ store }) });
    }
}
