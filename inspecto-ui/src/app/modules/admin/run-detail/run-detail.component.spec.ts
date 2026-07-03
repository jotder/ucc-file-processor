import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { AuditRow, LensService, RunsService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RunDetailComponent } from './run-detail.component';

const BATCH: AuditRow = { batch_id: 'b1', status: 'SUCCESS' };

function create() {
    TestBed.configureTestingModule({
        imports: [RunDetailComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ActivatedRoute,
                useValue: { snapshot: { paramMap: convertToParamMap({ name: 'cdr_ingest' }) } },
            },
            {
                provide: RunsService,
                useValue: {
                    batches: () => of([BATCH]),
                    files: () => of([]),
                    pending: () => of(null),
                    lineage: () => of([]),
                    quarantine: () => of([]),
                    commits: () => of([]),
                    reprocess: () => of({}),
                },
            },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(RunDetailComponent);
    fixture.detectChanges(); // runs ngOnInit (batches tab loads)
    return fixture;
}

describe('RunDetailComponent', () => {
    // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads the batches tab on init', () => {
        const c = create().componentInstance;
        expect(c.rows).toEqual([BATCH]);
    });

    it('shows Reprocess in the default (Builder) lens on the Batches tab', () => {
        const c = create().componentInstance;
        expect(c.auditRowActions.map((a) => a.hint)).toEqual(['Lineage & details', 'Reprocess this batch']);
    });

    it('hides Reprocess (keeps Lineage & details) in the Business (read-only) lens', () => {
        const c = create().componentInstance;
        TestBed.inject(LensService).selectLens('business');
        expect(c.auditRowActions.map((a) => a.hint)).toEqual(['Lineage & details']);
    });

    it('the Business lens blocks reprocessRow even when called directly', () => {
        const c = create().componentInstance;
        TestBed.inject(LensService).selectLens('business');
        const spy = vi.spyOn(TestBed.inject(RunsService), 'reprocess');
        c.reprocessRow(BATCH);
        expect(spy).not.toHaveBeenCalled();
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
