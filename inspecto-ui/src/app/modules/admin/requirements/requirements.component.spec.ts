import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { LensService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { buildRequirement, Requirement, RequirementsService } from 'app/inspecto/requirement';
import { RequirementsComponent } from './requirements.component';

const REQ: Requirement = buildRequirement('Daily churn KPI', 'kpi', 'Track churn by region.');

interface CreateOptions {
    list?: Requirement[];
    dialogOpen?: ReturnType<typeof vi.fn>;
    create?: ReturnType<typeof vi.fn>;
    decide?: ReturnType<typeof vi.fn>;
    deliver?: ReturnType<typeof vi.fn>;
}

function create(opts: CreateOptions = {}) {
    const dialogOpen = opts.dialogOpen ?? vi.fn(() => ({ afterClosed: () => of(undefined) }));
    TestBed.configureTestingModule({
        imports: [RequirementsComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: RequirementsService,
                useValue: {
                    list: () => of(opts.list ?? [REQ]),
                    create: opts.create ?? (() => of(REQ)),
                    decide: opts.decide ?? (() => of(REQ)),
                    deliver: opts.deliver ?? (() => of(REQ)),
                },
            },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    // DataTableComponent (used in the template) also provides/injects MatDialog; a plain providers[]
    // entry can lose to that transitive registration, so override it explicitly instead.
    TestBed.overrideProvider(MatDialog, { useValue: { open: dialogOpen } });
    const fixture = TestBed.createComponent(RequirementsComponent);
    fixture.detectChanges(); // runs ngOnInit (list load)
    return fixture;
}

describe('RequirementsComponent', () => {
    // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads requirements on init', () => {
        const c = create().componentInstance;
        expect(c.requirements()).toEqual([REQ]);
    });

    it('shows the empty state when there are none', () => {
        const fixture = create({ list: [] });
        expect(fixture.nativeElement.textContent).toContain('No requirements yet');
    });

    it('opens the submit dialog and creates on a result', () => {
        const create$ = vi.fn(() => of(REQ));
        const dialogOpen = vi.fn(() => ({ afterClosed: () => of({ title: 'x', kind: 'kpi', description: 'y' }) }));
        const fixture = create({ list: [], create: create$, dialogOpen });
        fixture.componentInstance.submit();
        expect(create$).toHaveBeenCalledWith(expect.objectContaining({ title: 'x', kind: 'kpi' }));
    });

    it('opens the decision dialog and no-ops in the Business (read-only) lens even if the dialog returns a result', () => {
        const decide = vi.fn(() => of(REQ));
        const dialogOpen = vi.fn(() => ({ afterClosed: () => of({ action: 'decide', accept: true }) }));
        const fixture = create({ decide, dialogOpen });
        TestBed.inject(LensService).selectLens('business');
        fixture.componentInstance.openDetail(REQ);
        expect(decide).not.toHaveBeenCalled();
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
