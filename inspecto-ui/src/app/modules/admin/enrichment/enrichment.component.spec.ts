import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { AuditRow, EnrichmentJobView, EnrichmentService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { EnrichmentComponent } from './enrichment.component';

const JOB = {
    name: 'geo_enrich', onPipeline: 'cdr_ingest', eventTriggered: true, scheduleTriggered: false,
    runCount: 12, lastRunStatus: 'SUCCESS', lastRunTime: '2026-06-17 10:00:00',
} as unknown as EnrichmentJobView;
const RUNS = [{ runId: 'r1', rows: 100 }] as unknown as AuditRow[];

function create(list: Observable<EnrichmentJobView[]> = of([JOB])) {
    const stub = {
        list: () => list,
        runs: () => of(RUNS),
        lineage: () => of(RUNS),
        report: () => of(null),
    } as unknown as EnrichmentService;
    TestBed.configureTestingModule({
        imports: [EnrichmentComponent],
        providers: [
            provideNoopAnimations(),
            { provide: EnrichmentService, useValue: stub },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(EnrichmentComponent);
    fixture.detectChanges();   // runs ngOnInit (jobs load)
    return fixture;
}

describe('EnrichmentComponent', () => {
    it('loads the enrichment jobs list', () => {
        const c = create().componentInstance;
        expect(c.jobs.length).toBe(1);
        expect(c.unavailable).toBe(false);
    });

    it('flags unavailable when the endpoint 404s', () => {
        const c = create(throwError(() => ({ status: 404 }))).componentInstance;
        expect(c.unavailable).toBe(true);
        expect(c.jobs.length).toBe(0);
    });

    it('selecting a job loads its runs into the detail grid', () => {
        const c = create().componentInstance;
        c.onRowClick(JOB);
        expect(c.selected?.name).toBe('geo_enrich');
        expect(c.activeTab).toBe('runs');
        expect(c.rows.length).toBe(1);
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
