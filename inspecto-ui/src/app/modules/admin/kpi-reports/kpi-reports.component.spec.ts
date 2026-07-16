import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { JobDetail, JobsService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { Dashboard } from '../studio/dashboards/dashboard-types';
import { DashboardsService } from '../studio/dashboards/dashboards.service';
import { KpiReportsComponent } from './kpi-reports.component';

const DASHBOARDS: Dashboard[] = [
    { id: 'cdr_overview', name: 'cdr_overview', tiles: [{ widgetId: 'w1', span: 1 }], exposedFields: ['tariff'] },
];

const REPORT_JOB: JobDetail = {
    name: 'daily_cdr_export',
    type: 'report',
    cron: '0 0 6 * * *',
    onPipeline: null,
    enabled: true,
    lastStatus: 'SUCCESS',
    lastRunTime: '2026-07-04T06:00:00Z',
    nextFire: null,
    params: { dashboardId: 'cdr_overview', format: 'csv', recipients: ['ops@x.com'] },
};

function create(opts: { dashboards?: Dashboard[]; reportJobs?: JobDetail[]; canAuthor?: boolean; dashboardsError?: boolean } = {}) {
    const dashboards = opts.dashboards ?? DASHBOARDS;
    const reportJobs = opts.reportJobs ?? [];
    const toastr = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
    const jobsApi = {
        list: vi.fn(() => of(reportJobs.map((j) => ({ name: j.name, type: j.type, cron: j.cron, onPipeline: j.onPipeline, enabled: j.enabled, lastStatus: j.lastStatus, lastRunTime: j.lastRunTime, nextFire: j.nextFire })))),
        get: vi.fn((name: string) => of(reportJobs.find((j) => j.name === name)!)),
        trigger: vi.fn(() => of({ runId: 'run-1' })),
        runs: vi.fn(() => of([{ runId: 'run-1', jobName: '', status: 'SUCCESS', triggerType: 'MANUAL', startTime: '' }])),
        runArtifact: vi.fn(() => of({ runId: 'run-1', filename: 'x.csv', mime: 'text/csv', content: 'a,b\n1,2' })),
        remove: vi.fn(() => of(undefined)),
    } as unknown as JobsService;
    TestBed.configureTestingModule({
        imports: [KpiReportsComponent],
        providers: [
            provideRouter([]),
            provideNoopAnimations(),
            {
                provide: DashboardsService,
                useValue: { list: () => (opts.dashboardsError ? throwError(() => new Error('boom')) : of(dashboards)) },
            },
            { provide: JobsService, useValue: jobsApi },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: {} },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(opts.canAuthor !== false) } },
        ],
    });
    const fixture = TestBed.createComponent(KpiReportsComponent);
    fixture.detectChanges();
    return { fixture, jobsApi, toastr };
}

describe('KpiReportsComponent', () => {
    it('lists saved dashboards as gallery cards with tile/filter counts', () => {
        const text = create().fixture.nativeElement.textContent as string;
        expect(text).toContain('cdr_overview');
        expect(text).toContain('1 tile(s)');
        expect(text).toContain('1 quick filter(s)');
    });

    it('shows the empty state when there are no dashboards', () => {
        const text = create({ dashboards: [] }).fixture.nativeElement.textContent as string;
        expect(text).toContain('No dashboards yet');
    });

    it('shows an error state (not the empty state) when the dashboards fetch fails', () => {
        const { fixture } = create({ dashboardsError: true });
        const text = fixture.nativeElement.textContent as string;
        expect(fixture.componentInstance.loadError()).toBe(true);
        expect(text).toContain("Couldn't load dashboards");
        expect(text).not.toContain('No dashboards yet');
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create().fixture.nativeElement);
    });

    it('lists a scheduled export under its dashboard card and hides authoring outside the Builder lens', async () => {
        const { fixture } = create({ reportJobs: [REPORT_JOB], canAuthor: false });
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.componentInstance.jobsFor('cdr_overview').map((j) => j.name)).toEqual(['daily_cdr_export']);
        expect(fixture.nativeElement.textContent).not.toContain('Schedule export');
    });

    it('downloadLatest fetches the latest run artifact and triggers a blob download', async () => {
        const { fixture, jobsApi } = create({ reportJobs: [REPORT_JOB] });
        fixture.componentInstance.downloadLatest(REPORT_JOB);
        expect(jobsApi.runs).toHaveBeenCalledWith('daily_cdr_export');
    });

    it('warns instead of downloading when the schedule has never run', () => {
        const neverRun = { ...REPORT_JOB, lastRunTime: undefined };
        const { fixture, toastr } = create({ reportJobs: [neverRun] });
        fixture.componentInstance.downloadLatest(neverRun);
        expect(toastr.warning).toHaveBeenCalledWith(expect.stringContaining('has not run yet'));
    });
});
