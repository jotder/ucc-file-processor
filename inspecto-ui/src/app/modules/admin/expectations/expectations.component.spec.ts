import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { Expectation, ExpectationsService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ExpectationsComponent } from './expectations.component';

const ROW: Expectation = {
    name: 'cdr_duration_range',
    description: 'Call duration must be 0–86400 s',
    targetType: 'pipeline',
    target: 'cdr_ingest',
    column: 'duration_s',
    kind: 'range',
    min: 0,
    max: 86_400,
    pattern: null,
    refDataset: null,
    refColumn: null,
    severity: 'MAJOR',
    enabled: true,
    lastResult: null,
    createdAt: 1,
    updatedAt: 1,
};

async function create(opts: { rows?: Expectation[]; canAuthor?: boolean; api?: Partial<Record<keyof ExpectationsService, unknown>> } = {}) {
    const toastr = { error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        list: vi.fn(() => of(opts.rows ?? [ROW])),
        evaluate: vi.fn(),
        evaluateAll: vi.fn(),
        remove: vi.fn(),
        ...opts.api,
    } as unknown as ExpectationsService;
    TestBed.configureTestingModule({
        imports: [ExpectationsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ExpectationsService, useValue: api },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: {} },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(opts.canAuthor !== false) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(ExpectationsComponent);
    fixture.detectChanges();
    return { fixture, api, toastr };
}

describe('ExpectationsComponent', () => {
    it('lists expectations with no a11y violations', async () => {
        const { fixture } = await create();
        expect(fixture.componentInstance.rows).toEqual([ROW]);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the empty state with no a11y violations', async () => {
        const { fixture } = await create({ rows: [] });
        expect(fixture.nativeElement.textContent).toContain('No expectations yet');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('hides authoring actions outside the Builder lens (capability seam)', async () => {
        const { fixture } = await create({ canAuthor: false });
        const hints = fixture.componentInstance.rowActions.map((a) => a.hint);
        expect(hints).toEqual(['Run check now']);
        expect(fixture.nativeElement.textContent).not.toContain('New expectation');
    });

    it('a failed evaluation warns that an Incident was raised and patches the row', async () => {
        const failed: Expectation = { ...ROW, lastResult: { status: 'FAILED', violations: 12, checkedAt: 2 } };
        const { fixture, toastr } = await create({ api: { evaluate: vi.fn(() => of(failed)) } });
        fixture.componentInstance.evaluate(ROW);
        expect(toastr.warning).toHaveBeenCalledWith(expect.stringContaining('an Incident was raised'));
        expect(fixture.componentInstance.rows[0].lastResult?.status).toBe('FAILED');
    });

    it('the sweep reports the failure count', async () => {
        const failed: Expectation = { ...ROW, lastResult: { status: 'FAILED', violations: 12, checkedAt: 2 } };
        const { fixture, toastr } = await create({ api: { evaluateAll: vi.fn(() => of([failed])) } });
        fixture.componentInstance.evaluateAll();
        expect(toastr.warning).toHaveBeenCalledWith(expect.stringContaining('1 expectation(s) FAILED'));
    });
});
