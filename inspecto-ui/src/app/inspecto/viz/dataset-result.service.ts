import { Injectable } from '@angular/core';
import { ColumnMeta } from 'app/inspecto/query';
import { SqlRunResult } from 'app/inspecto/data-table/sql/sql-run';
import { QuerySpec } from './viz-types';
import { runSpec } from './query-spec';

/**
 * De-dupes/caches identical {@link QuerySpec} runs so N widgets sharing a dataset + cross-filter compute
 * once, not N times (e.g. dashboard tiles, gallery thumbnails). M1 form: in-memory over the offline AlaSQL
 * run. M2 (backend, backlog) swaps `runSpec()` for a real HTTP call behind the same `run()` signature —
 * callers ({@link WidgetHostComponent}) never change.
 */
@Injectable({ providedIn: 'root' })
export class DatasetResultService {
    private cache = new Map<string, Promise<SqlRunResult>>();

    /** Run `spec` — an identical spec already in flight or resolved is reused, not re-run. */
    run(spec: QuerySpec, rows: Record<string, unknown>[], cols: ColumnMeta[] = []): Promise<SqlRunResult> {
        const key = hashSpec(spec);
        const cached = this.cache.get(key);
        if (cached) return cached;
        const promise = runSpec(spec, rows, cols);
        this.cache.set(key, promise);
        // A failed run shouldn't stick forever — drop it so the next call retries instead of replaying the error.
        promise.catch(() => this.cache.delete(key));
        return promise;
    }

    /** Drop all cached results — call when the data a spec would read over has changed. */
    clear(): void {
        this.cache.clear();
    }
}

/**
 * A stable cache key for a {@link QuerySpec}. Relies on the query builders (`buildXyQuery` /
 * `buildTableQuery` / `buildValueQuery`) always constructing the spec object with the same key order —
 * true today, so a full stable-stringify (sorting keys) isn't needed for this M1 scope.
 */
function hashSpec(spec: QuerySpec): string {
    return JSON.stringify(spec);
}
