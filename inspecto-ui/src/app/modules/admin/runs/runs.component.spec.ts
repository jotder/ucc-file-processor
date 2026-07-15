import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { LensService, RunResult, RunsService, RunView } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RunsComponent } from './runs.component';

const RUN: RunView = { name: 'cdr_ingest', configPath: 'cdr_ingest.toon', paused: false, committedBatches: 5 };
const RESULT: RunResult = { total: 3, failed: 0 };

function create(runs: RunView[] = [RUN], paramMap: Observable<ParamMap> = of(convertToParamMap({}))) {
    TestBed.configureTestingModule({
        imports: [RunsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            // The `/runs(/:name)` param drives the detail side panel (R5).
            { provide: ActivatedRoute, useValue: { paramMap, snapshot: { paramMap: convertToParamMap({}) } } },
            {
                provide: RunsService,
                useValue: {
                    list: () => of(runs),
                    trigger: () => of({ runId: 'run-1' }),   // v1 async contract (W5b): 202 + runId
                    runAll: () => of({ cdr_ingest: RESULT }),
                    pause: () => of({ pipeline: 'cdr_ingest', paused: true }),
                    resume: () => of({ pipeline: 'cdr_ingest', paused: false }),
                    // Consumed by the embedded run-detail side panel (initial Batches tab).
                    batches: () => of([]),
                    files: () => of([]),
                    pending: () => of(null),
                },
            },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
        ],
    });
    const fixture = TestBed.createComponent(RunsComponent);
    fixture.detectChanges(); // runs ngOnInit (list load)
    return fixture;
}

describe('RunsComponent', () => {
    // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads runs on init', () => {
        const c = create().componentInstance;
        expect(c.runs).toEqual([RUN]);
    });

    it('shows Run all and all row actions in the default (Builder) lens', () => {
        const fixture = create();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Run all'))).toBe(true);
        expect(fixture.componentInstance.rowActions.map((a) => (typeof a.hint === 'function' ? a.hint(RUN) : a.hint)))
            .toEqual(['Trigger', 'Pause', 'Reprocess batch', 'Open detail']);
    });

    it('hides Run all and every action but Open detail in the Business (read-only) lens', () => {
        const fixture = create();
        TestBed.inject(LensService).selectLens('business');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Run all'))).toBe(false);
        expect(fixture.componentInstance.rowActions.map((a) => (typeof a.hint === 'function' ? a.hint(RUN) : a.hint)))
            .toEqual(['Open detail']);
    });

    it('the Business lens blocks trigger/runAll/togglePause/openReprocess even when called directly', async () => {
        const c = create().componentInstance;
        TestBed.inject(LensService).selectLens('business');
        const spy = vi.spyOn(TestBed.inject(RunsService), 'trigger');
        await c.trigger('cdr_ingest');
        expect(spy).not.toHaveBeenCalled();
        await c.runAll();
        await c.togglePause(RUN);
        expect(RUN.paused).toBe(false);
        c.openReprocess('cdr_ingest');
    });

    it('opens the detail side panel on a name route param and closes it when the param clears (R5)', () => {
        const params = new BehaviorSubject<ParamMap>(convertToParamMap({}));
        const fixture = create([RUN], params);
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        expect(c.detailName()).toBeNull();
        expect(el.querySelector('app-run-detail')).toBeNull();

        params.next(convertToParamMap({ name: 'cdr_ingest' }));   // deep link /runs/cdr_ingest
        fixture.detectChanges();
        expect(c.detailName()).toBe('cdr_ingest');
        expect(el.querySelector('app-run-detail')).toBeTruthy();
        expect(c.runs).toEqual([RUN]); // the list survives the detail opening

        params.next(convertToParamMap({}));                       // back to /runs
        fixture.detectChanges();
        expect(c.detailName()).toBeNull();
        expect(el.querySelector('app-run-detail')).toBeNull();
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
