import { Injectable, inject } from '@angular/core';
import { firstValueFrom, forkJoin, of } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ReconApiService, ReconServerConfig } from 'app/inspecto/api';
import {
    aggregateRecon, Reconciliation, ReconBreakSets, reconBreakSets, ReconRunResult, SideKey,
} from 'app/inspecto/reconciliation';
import { evaluateRows } from 'app/inspecto/query';
import { Dataset } from '../studio/datasets/dataset-types';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { SAMPLE_SOURCES } from '../studio/datasets/dataset-sources';

/**
 * Reconciliation execution seam — the recon analogue of `DatasetResultService`: when the Studio domain
 * is mock-served (`environment.mockStudio`) the comparison runs in-browser over the datasets' sample
 * rows (the offline engine in `recon-board.ts`); against a real backend it executes server-side in
 * DuckDB via `POST /recon/run` / `/recon/breaks`. Same signatures either way — the Board and the Breaks
 * page never change. This replaces the C9 review-sheet's `datasetRows()` mock seam.
 */
@Injectable({ providedIn: 'root' })
export class ReconExecService {
    private api = inject(ReconApiService);
    private datasets = inject(DatasetsService);

    /** Run the Board aggregate comparison. */
    async run(recon: Reconciliation): Promise<ReconRunResult> {
        if (!environment.mockStudio) return firstValueFrom(this.api.run(serverConfig(recon)));
        const { left, right, third } = await this.rows(recon);
        return aggregateRecon(recon, left, right, third);
    }

    /**
     * The Break sets at the recon grain for one anchor-relative pair, optionally scoped to a Board
     * dimension path. {@code side} picks the compared side ('b' default, or 'c' on a 3-way recon).
     */
    async breaks(
        recon: Reconciliation,
        path?: Record<string, string> | null,
        type?: 'missing_left' | 'missing_right' | 'value_break' | null,
        side: SideKey = 'b',
    ): Promise<ReconBreakSets> {
        if (!environment.mockStudio) return firstValueFrom(this.api.breaks(serverConfig(recon), path, type, side));
        const { left, right, third } = await this.rows(recon);
        return reconBreakSets(recon, left, right, path, type, side, third);
    }

    /** Offline row resolution — the datasets' sample-source rows through their Query Core when virtual. */
    private async rows(recon: Reconciliation): Promise<{
        left: Record<string, unknown>[]; right: Record<string, unknown>[]; third: Record<string, unknown>[] | null;
    }> {
        const { left, right, third } = await firstValueFrom(
            forkJoin({
                left: this.datasets.get(recon.leftDataset),
                right: this.datasets.get(recon.rightDataset),
                third: recon.thirdDataset ? this.datasets.get(recon.thirdDataset) : of(null),
            }),
        );
        return { left: datasetRows(left), right: datasetRows(right), third: third ? datasetRows(third) : null };
    }
}

/** Map the UI model to the server config (`/recon/*` accepts the v1 left/right form too — send v2). */
export function serverConfig(recon: Reconciliation): ReconServerConfig {
    return {
        datasets: recon.thirdDataset
            ? [recon.leftDataset, recon.rightDataset, recon.thirdDataset]
            : [recon.leftDataset, recon.rightDataset],
        keyColumns: recon.keyColumns,
        compareColumns: recon.compareColumns.map((c) => ({
            column: c.column,
            agg: c.agg ?? 'sum',
            toleranceType: c.toleranceType,
            tolerance: c.tolerance,
        })),
        includeRecordCount: true,
    };
}

/** Resolve an authored dataset to its (mock) rows — sample-source rows, Query-Core-filtered when virtual. */
export function datasetRows(ds: Dataset | null): Record<string, unknown>[] {
    if (!ds) return [];
    const rows = SAMPLE_SOURCES[ds.sourceName] ?? [];
    if (ds.kind === 'virtual' && ds.query) return evaluateRows(ds.query, { name: ds.sourceName, rows });
    return rows;
}
