import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dashboard } from '../studio/dashboards/dashboard-types';
import { DashboardsService } from '../studio/dashboards/dashboards.service';
import { KpiReportsComponent } from './kpi-reports.component';

const DASHBOARDS: Dashboard[] = [
    { id: 'cdr_overview', name: 'cdr_overview', tiles: [{ widgetId: 'w1', span: 1 }], exposedFields: ['tariff'] },
];

function create(dashboards: Dashboard[]) {
    TestBed.configureTestingModule({
        imports: [KpiReportsComponent],
        providers: [
            provideRouter([]),
            provideNoopAnimations(),
            { provide: DashboardsService, useValue: { list: () => of(dashboards) } },
        ],
    });
    const fixture = TestBed.createComponent(KpiReportsComponent);
    fixture.detectChanges();
    return fixture;
}

describe('KpiReportsComponent', () => {
    it('lists saved dashboards as gallery cards with tile/filter counts', () => {
        const text = create(DASHBOARDS).nativeElement.textContent as string;
        expect(text).toContain('cdr_overview');
        expect(text).toContain('1 tile(s)');
        expect(text).toContain('1 quick filter(s)');
    });

    it('shows the empty state when there are no dashboards', () => {
        const text = create([]).nativeElement.textContent as string;
        expect(text).toContain('No dashboards yet');
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create(DASHBOARDS).nativeElement);
    });
});
