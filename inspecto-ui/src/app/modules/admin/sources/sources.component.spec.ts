import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { Observable, of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AcquisitionMetrics, AcquisitionMetricsService, RunsService, SourceView, SourcesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SourcesComponent } from './sources.component';

const SOURCE = {
    pipeline: 'cdr_ingest', id: 'sftp_in', connector: 'sftp', connection: 'sftp_edge',
    duplicateMode: 'CONTENT_HASH', incrementalWatermark: 'mtime', dbWatermarkCurrent: null,
    fetchParallel: 4, guarantee: 'AT_LEAST_ONCE', includes: [], excludes: [],
} as unknown as SourceView;

const metric = (v: number) => ({ series: [{ value: v }] });
const METRICS = {
    inspecto_files_discovered_total: metric(100),
    inspecto_files_downloaded_total: metric(90),
    inspecto_downloads_failed_total: metric(2),
    inspecto_watermark_skipped_total: metric(8),
    inspecto_bytes_transferred_total: metric(1048576),
    inspecto_active_connections: metric(3),
} as unknown as AcquisitionMetrics;

let toastr: { success: ReturnType<typeof vi.fn>; warning: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

function create(
    list: Observable<SourceView[]> = of([SOURCE]),
    metrics: Observable<AcquisitionMetrics> = of(METRICS),
) {
    toastr = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [SourcesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: SourcesService, useValue: { list: () => list } },
            { provide: AcquisitionMetricsService, useValue: { get: () => metrics } },
            { provide: RunsService, useValue: { trigger: () => of({ runId: 'run-1' }) } },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(SourcesComponent);
    fixture.detectChanges();   // runs ngOnInit (list + metrics load)
    return fixture;
}

describe('SourcesComponent', () => {
    it('builds the acquisition metric cards and the discovered/downloaded/failed chart', () => {
        const c = create().componentInstance;
        expect(c.cards.length).toBe(6);
        expect(c.cards[0]).toEqual({ label: 'Files discovered', value: '100' });
        expect(c.discoveredData?.datasets[0].data).toEqual([100, 90, 2]);
    });

    it('flags unavailable when the sources endpoint 404s', () => {
        const c = create(throwError(() => ({ status: 404 }))).componentInstance;
        expect(c.unavailable).toBe(true);
        expect(c.sources.length).toBe(0);
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('trigger toasts that the run started (v1 async contract)', async () => {
        const fixture = create();
        await fixture.componentInstance.trigger(SOURCE);
        expect(toastr.success).toHaveBeenCalledWith('Pipeline "cdr_ingest" run started.');
        expect(toastr.warning).not.toHaveBeenCalled();
    });
});
