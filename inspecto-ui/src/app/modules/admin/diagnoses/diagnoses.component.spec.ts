import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AssistService, Diagnosis } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { DiagnosesComponent } from './diagnoses.component';

const DIAGNOSIS = {
    epochMillis: 1,
    pipeline: 'cdr_ingest',
    batchId: 'b-1',
    severity: 'CRITICAL',
    rootCause: 'Schema drift on column msisdn',
    heuristicOnly: false,
} as Diagnosis;

async function create(overrides: Partial<Record<keyof AssistService, unknown>> = {}) {
    const toastr = { error: vi.fn(), warning: vi.fn() };
    const dialog = { open: vi.fn() };
    const api = { diagnoses: vi.fn(() => of([DIAGNOSIS])), ...overrides } as unknown as AssistService;
    TestBed.configureTestingModule({
        imports: [DiagnosesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AssistService, useValue: api },
            { provide: MatDialog, useValue: dialog },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    // A component-level provider would shadow the root useValue — override wins at every level.
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(DiagnosesComponent);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api, toastr, dialog };
}

describe('DiagnosesComponent', () => {
    it('loads recent diagnoses on init with the default limit', async () => {
        const { fixture, api } = await create();
        expect(api.diagnoses).toHaveBeenCalledWith(50);
        expect(fixture.componentInstance.diagnoses).toEqual([DIAGNOSIS]);
    });

    it('row click opens the detail dialog', async () => {
        const { fixture, dialog } = await create();
        fixture.componentInstance.openDetail(DIAGNOSIS);
        expect(dialog.open).toHaveBeenCalled();
    });

    it('degrades to an empty grid + plain failure toast when the load fails', async () => {
        const { fixture, toastr } = await create({ diagnoses: () => throwError(() => ({ status: 500 })) });
        expect(fixture.componentInstance.diagnoses).toEqual([]);
        expect(toastr.error).toHaveBeenCalledWith('Failed to load diagnoses');
    });

    it('renders the loaded state with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
