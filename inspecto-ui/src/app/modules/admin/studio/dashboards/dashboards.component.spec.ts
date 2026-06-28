import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';
import { DashboardsComponent } from './dashboards.component';

const D1: Dashboard = { id: 'cdr_overview', name: 'cdr_overview', tiles: [{ chartId: 'bar1', span: 1 }], filter: null };

function create(dashboards: Dashboard[] = [D1]) {
    const remove = vi.fn(() => of(null));
    TestBed.configureTestingModule({
        imports: [DashboardsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: DashboardsService, useValue: { list: () => of(dashboards), remove } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
        ],
    });
    return { fixture: TestBed.createComponent(DashboardsComponent), remove };
}

describe('DashboardsComponent', () => {
    it('loads and filters dashboards', () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.visibleDashboards()).toHaveLength(1);
        fixture.componentInstance.filterText.set('zzz');
        expect(fixture.componentInstance.visibleDashboards()).toHaveLength(0);
    });

    it('deletes after confirmation', async () => {
        const { fixture, remove } = create();
        fixture.detectChanges();
        await fixture.componentInstance.remove(D1);
        expect(remove).toHaveBeenCalledWith('cdr_overview');
    });

    it('renders the empty state with no a11y violations', async () => {
        const { fixture } = create([]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
