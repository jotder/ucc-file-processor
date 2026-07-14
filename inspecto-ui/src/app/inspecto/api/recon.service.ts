import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ReconBreakSets, ReconRunResult } from 'app/inspecto/reconciliation/recon-board';
import { apiUrl } from './api-base';

/** One dataset's column inventory as `/recon/columns` reports it. */
export interface ReconDatasetColumns {
    dataset: string;
    columns: { name: string; type: string; numeric: boolean }[];
}

/** A column whose normalized name exists on every side — a suggested unified binding. */
export interface ReconColumnMatch {
    name: string;
    numeric: boolean;
    columns: Record<string, string>;
}

export interface ReconColumnsResult {
    datasets: ReconDatasetColumns[];
    matches: ReconColumnMatch[];
}

/** The server-side reconciliation config (`docs/superpower/reconciliation-board-design.md` §3). */
export interface ReconServerConfig {
    datasets: string[];
    keyColumns: string[];
    compareColumns: { column: string; agg?: string; toleranceType?: string; tolerance?: number }[];
    includeRecordCount?: boolean;
    columnMap?: Record<string, Record<string, string>>;
    filters?: Record<string, string>;
}

/**
 * Reconciliation execution API (DAT-7) — the server-side comparison over two Datasets' relations.
 * Space-agnostic (`spaceInterceptor` scopes `/recon/*`); the offline mirror is
 * `app/inspecto/reconciliation/recon-board.ts` behind `ReconExecService`.
 */
@Injectable({ providedIn: 'root' })
export class ReconApiService {
    private http = inject(HttpClient);

    columns(datasets: string[]): Observable<ReconColumnsResult> {
        return this.http.post<ReconColumnsResult>(apiUrl('/recon/columns'), { datasets });
    }

    run(config: ReconServerConfig, limit?: number): Observable<ReconRunResult> {
        return this.http.post<ReconRunResult>(apiUrl('/recon/run'), { config, ...(limit ? { limit } : {}) });
    }

    breaks(
        config: ReconServerConfig,
        path?: Record<string, string> | null,
        type?: string | null,
        side?: string | null,
        limit?: number,
        offset?: number,
    ): Observable<ReconBreakSets> {
        return this.http.post<ReconBreakSets>(apiUrl('/recon/breaks'), {
            config,
            ...(path ? { path } : {}),
            ...(type ? { type } : {}),
            ...(side ? { side } : {}),
            ...(limit ? { limit } : {}),
            ...(offset ? { offset } : {}),
        });
    }
}
