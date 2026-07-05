import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { JobRun, JobsService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobRunsDialog } from './job-runs.dialog';

const RUNS: JobRun[] = [
    {
        jobName: 'rollup',
        runId: 'r1',
        status: 'SUCCESS',
        startTime: '2026-06-17 10:00:00',
        endTime: '2026-06-17 10:00:01',
        durationMs: 1000,
        triggerType: 'schedule',
        error: null,
    },
];

function create() {
    const runs = vi.fn(() => of(RUNS));
    TestBed.configureTestingModule({
        imports: [JobRunsDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { job: 'rollup' } },
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: JobsService, useValue: { runs } },
            InspectoGridThemeService,
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(JobRunsDialog);
    fixture.detectChanges();   // runs ngOnInit (loads run history)
    return { fixture, runs };
}

describe('JobRunsDialog', () => {
    it('loads the run history for the job on init', () => {
        const { fixture, runs } = create();
        expect(runs).toHaveBeenCalledWith('rollup');
        expect(fixture.componentInstance.loading).toBe(false);
        expect(fixture.componentInstance.runs).toEqual(RUNS);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Run history — rollup');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
