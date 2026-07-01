import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ReportsService, RunStatus, StatusReport } from 'app/inspecto/api';
import { ProcessingStatusComponent } from './processing-status.component';

const PIPELINES: RunStatus[] = [
    { pipeline: 'cdr_ingest', paused: false, committedBatches: 120, quarantineFiles: 2, lastBatchId: 'batch-1000', lastBatchStatus: 'COMMITTED', lastBatchTime: '2026-06-30T00:00:00.000Z' },
    { pipeline: 'billing_daily', paused: true, committedBatches: 231, quarantineFiles: 0, lastBatchId: 'batch-1003', lastBatchStatus: 'FAILED', lastBatchTime: '2026-06-29T00:00:00.000Z' },
];

const REPORT: StatusReport = {
    generatedAt: '2026-06-30T00:00:00.000Z',
    pipelineCount: PIPELINES.length,
    pausedCount: 1,
    totalCommittedBatches: 351,
    totalQuarantineFiles: 2,
    pipelines: PIPELINES,
};

function create(report: StatusReport | null = REPORT) {
    TestBed.configureTestingModule({
        imports: [ProcessingStatusComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ReportsService, useValue: { status: () => (report ? of(report) : of()) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    return TestBed.createComponent(ProcessingStatusComponent);
}

describe('ProcessingStatusComponent', () => {
    it('loads the status report into summary cards and rows', () => {
        const fixture = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.report?.pipelines).toHaveLength(2);
        expect(c.cards.map((x) => x.value)).toEqual(['2', '1', '351', '2']);
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
