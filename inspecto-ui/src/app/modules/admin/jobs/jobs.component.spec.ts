import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { beforeEach, describe, expect, it } from 'vitest';
import { JobFailureDay, JobMetrics, JobRunRow, JobView, JobsService, LensService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobsComponent, fmtDuration } from './jobs.component';

const METRICS: JobMetrics = { total: 4, success: 3, failed: 1, successRate: 0.75, p50Ms: 20, p95Ms: 40, meanMs: 25 };
const RUNS: JobRunRow[] = [
    { runId: 'r1', job: 'rollup', type: 'ENRICH', trigger: 'schedule', startTime: '2026-06-17 10:00:00', endTime: '2026-06-17 10:00:01', status: 'SUCCESS', durationMs: 20, message: 'ok' },
];
const FAILS: JobFailureDay[] = [{ day: '2026-06-17', total: 4, failed: 1 }];

/** Build the component over a stub service. `reporting` decides whether the T27 endpoints resolve or 404. */
function create(
    reporting: 'ok' | '404',
    listResult: Observable<JobView[]> = of([]),
    paramMap: Observable<ParamMap> = of(convertToParamMap({})),
) {
    const report404 = () => throwError(() => ({ status: 404 }));
    const stub = {
        list: () => listResult,
        metrics: () => (reporting === 'ok' ? of(METRICS) : report404()),
        recentRuns: () => (reporting === 'ok' ? of(RUNS) : report404()),
        failures: () => (reporting === 'ok' ? of(FAILS) : report404()),
        // Consumed by the embedded job-detail side panel.
        get: () => of({ name: 'rollup', type: 'enrich', cron: '0 0 6 * * *', onPipeline: null, enabled: true, params: {} }),
        runs: () => of([]),
        runLogs: () => of({ logs: [], events: [] }),
    } as unknown as JobsService;
    TestBed.configureTestingModule({
        imports: [JobsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            // The `/jobs(/:name)` param drives the detail side panel (R5).
            {
                provide: ActivatedRoute,
                useValue: { paramMap, queryParamMap: of(convertToParamMap({})), snapshot: { paramMap: convertToParamMap({}) } },
            },
            { provide: JobsService, useValue: stub },
            { provide: ToastrService, useValue: {} },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(JobsComponent);
    fixture.detectChanges();   // runs ngOnInit (schedules load)
    return fixture;
}

describe('fmtDuration', () => {
    it('formats sub-second, second, and minute durations', () => {
        expect(fmtDuration(450)).toBe('450ms');
        expect(fmtDuration(1500)).toBe('1.5s');
        expect(fmtDuration(123000)).toBe('2m 03s');
        expect(fmtDuration(null)).toBe('—');
    });
});

describe('JobsComponent', () => {
    // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('lazy-loads the reporting projection when switching to reporting mode', () => {
        const c = create('ok').componentInstance;
        expect(c.metrics).toBeNull();
        c.setMode('reporting');
        expect(c.mode).toBe('reporting');
        expect(c.metrics?.total).toBe(4);
        expect(c.successPct).toBe(75);
        expect(c.runs.length).toBe(1);
        expect(c.chartData?.datasets.length).toBe(2);   // Total + Failed series
    });

    it('flags the reporting backend as disabled on a 404', () => {
        const c = create('404').componentInstance;
        c.setMode('reporting');
        expect(c.reportDisabled).toBe(true);
        expect(c.metrics).toBeNull();
        expect(c.runs.length).toBe(0);
    });

    it('renders an accessible empty state when reporting is unavailable', async () => {
        const fixture = create('404');
        fixture.componentInstance.setMode('reporting');
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('hides reschedule/edit/delete row actions in the Business (read-only) lens; keeps run/toggle', () => {
        const fixture = create('ok');
        const c = fixture.componentInstance;
        TestBed.inject(LensService).selectLens('business');
        const hints = c.scheduleActions.map((a) => (typeof a.hint === 'function' ? a.hint({ enabled: true } as JobView) : a.hint));
        expect(hints).toEqual(['Run now', 'Disable']);
    });

    it('opens the detail side panel on a name route param and closes it when the param clears (R5)', () => {
        const params = new BehaviorSubject<ParamMap>(convertToParamMap({}));
        const fixture = create('ok', of([]), params);
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        expect(c.detailName()).toBeNull();
        expect(el.querySelector('app-job-detail')).toBeNull();

        params.next(convertToParamMap({ name: 'rollup' }));   // deep link /jobs/rollup
        fixture.detectChanges();
        expect(c.detailName()).toBe('rollup');
        expect(el.querySelector('app-job-detail')).toBeTruthy();

        params.next(convertToParamMap({}));                   // back to /jobs
        fixture.detectChanges();
        expect(c.detailName()).toBeNull();
        expect(el.querySelector('app-job-detail')).toBeNull();
    });
});
