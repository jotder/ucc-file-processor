import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Reconciliation, ReconciliationsService } from 'app/inspecto/reconciliation';
import { ReconciliationsComponent } from './reconciliations.component';

const RECON: Reconciliation = {
    id: 'switch_vs_billing', name: 'switch vs billing', leftDataset: 'switch_cdr', rightDataset: 'billing_cdr',
    keyColumns: ['id'], compareColumns: [], breaks: [], lastRunAt: null,
};

function create(list: Reconciliation[] = [RECON], dialogOpen = vi.fn(() => ({ afterClosed: () => of(undefined) }))) {
    TestBed.configureTestingModule({
        imports: [ReconciliationsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ReconciliationsService, useValue: { list: () => of(list), create: () => of(RECON) } },
            { provide: ToastrService, useValue: { error: () => undefined } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    // DataTableComponent (template) also injects MatDialog — override wins over a plain providers[] entry.
    TestBed.overrideProvider(MatDialog, { useValue: { open: dialogOpen } });
    const fixture = TestBed.createComponent(ReconciliationsComponent);
    fixture.detectChanges();
    return { fixture, dialogOpen };
}

describe('ReconciliationsComponent', () => {
    it('loads reconciliations on init', () => {
        expect(create().fixture.componentInstance.reconciliations()).toEqual([RECON]);
    });

    it('shows the empty state when there are none', () => {
        expect(create([]).fixture.nativeElement.textContent).toContain('No reconciliations yet');
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create().fixture.nativeElement);
    });
});
