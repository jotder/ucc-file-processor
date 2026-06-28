import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Chart } from './chart-types';
import { ChartsService } from './charts.service';
import { ChartsComponent } from './charts.component';

const C1: Chart = { id: 'dur_by_tariff', name: 'dur_by_tariff', datasetId: 'cdr_sample', vizType: 'bar', controls: {} };

function create(charts: Chart[] = [C1]) {
    const remove = vi.fn(() => of(null));
    TestBed.configureTestingModule({
        imports: [ChartsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ChartsService, useValue: { list: () => of(charts), remove } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
        ],
    });
    return { fixture: TestBed.createComponent(ChartsComponent), remove };
}

describe('ChartsComponent', () => {
    it('loads charts on init and filters them', () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.visibleCharts()).toHaveLength(1);
        fixture.componentInstance.filterText.set('line');
        expect(fixture.componentInstance.visibleCharts()).toHaveLength(0);
    });

    it('deletes after confirmation', async () => {
        const { fixture, remove } = create();
        fixture.detectChanges();
        await fixture.componentInstance.remove(C1);
        expect(remove).toHaveBeenCalledWith('dur_by_tariff');
    });

    it('renders the empty state with no a11y violations', async () => {
        const { fixture } = create([]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
