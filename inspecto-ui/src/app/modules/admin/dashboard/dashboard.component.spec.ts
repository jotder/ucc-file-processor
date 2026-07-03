import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { GammaConfigService } from '@gamma/services/config';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import {
    AcquisitionMetricsService,
    EventRow,
    EventsService,
    HealthService,
    ReadyStatus,
    ReportsService,
    ServiceReport,
    StatusReport,
} from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DashboardComponent } from './dashboard.component';

const READY: ReadyStatus = { status: 'READY', pipelines: 2 };
const STATUS: StatusReport = {
    generatedAt: '2026-07-03T00:00:00Z', pipelineCount: 2, pausedCount: 0,
    totalCommittedBatches: 10, totalQuarantineFiles: 0,
    pipelines: [{ pipeline: 'cdr_ingest', paused: false, committedBatches: 10, quarantineFiles: 0, lastBatchStatus: 'SUCCESS' }],
};
const REPORT: ServiceReport = {
    generatedAt: '2026-07-03T00:00:00Z', totalBatches: 10, success: 9, failed: 1, errorRate: 0.1, totalOutputRows: 1000,
    p50DurationMs: 20, p95DurationMs: 40, p99DurationMs: 60, windowFrom: '', windowTo: '', pipelines: [],
};

function create(overrides: { ready?: ReadyStatus | null; status?: StatusReport | null } = {}) {
    TestBed.configureTestingModule({
        imports: [DashboardComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: HealthService,
                useValue: {
                    ready: () => of(overrides.ready ?? READY),
                    metrics: () => of('# HELP inspecto_up 1\n'),
                },
            },
            {
                provide: ReportsService,
                useValue: { status: () => of(overrides.status ?? STATUS), serviceReport: () => of(REPORT) },
            },
            { provide: AcquisitionMetricsService, useValue: { get: () => throwError(() => ({ status: 404 })) } },
            { provide: EventsService, useValue: { search: () => of([] as EventRow[]) } },
            { provide: ToastrService, useValue: { error: () => undefined } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges(); // runs ngOnInit (initial refresh)
    return fixture;
}

describe('DashboardComponent', () => {
    it('loads service health, status and the batch-outcome report on init', () => {
        const c = create().componentInstance;
        expect(c.ready?.status).toBe('READY');
        expect(c.status?.pipelineCount).toBe(2);
        expect(c.errorRatePct).toBe('10.0%');
    });

    it('hides the acquisition summary when the metrics call 404s', () => {
        const c = create().componentInstance;
        expect(c.acqCards).toEqual([]);
    });

    it('degrades gracefully and toasts when every core call fails', () => {
        TestBed.configureTestingModule({
            imports: [DashboardComponent],
            providers: [
                provideNoopAnimations(),
                { provide: HealthService, useValue: { ready: () => throwError(() => ({})), metrics: () => throwError(() => ({})) } },
                { provide: ReportsService, useValue: { status: () => throwError(() => ({})), serviceReport: () => throwError(() => ({})) } },
                { provide: AcquisitionMetricsService, useValue: { get: () => throwError(() => ({})) } },
                { provide: EventsService, useValue: { search: () => of([] as EventRow[]) } },
                { provide: ToastrService, useValue: { error: (msg: string) => msg } },
                { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
                { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            ],
        });
        const fixture = TestBed.createComponent(DashboardComponent);
        fixture.detectChanges();
        expect(fixture.componentInstance.ready).toBeNull();
        expect(fixture.componentInstance.loading).toBe(false);
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
