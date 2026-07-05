import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AddToDashboardData, AddToDashboardDialog } from './add-to-dashboard.dialog';
import { Dashboard } from '../dashboards/dashboard-types';

const D1: Dashboard = { id: 'ops_overview', name: 'ops_overview', tiles: [], filter: null };

function create(data: Partial<AddToDashboardData> = {}) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [AddToDashboardDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { widgetId: 'cost_by_tariff', dashboards: [D1], ...data } },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(AddToDashboardDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('AddToDashboardDialog', () => {
    it('defaults to "new dashboard" and blocks a duplicate id inline, then closes once unique', () => {
        const { c, ref } = create();
        c.form.controls.name.setValue('ops_overview');
        c.add();
        expect(ref.close).not.toHaveBeenCalled();
        expect(c.form.controls.name.hasError('duplicate')).toBe(true);
        c.form.controls.name.setValue('ops_overview_2');
        c.add();
        expect(ref.close).toHaveBeenCalledWith({ newName: 'ops_overview_2' });
    });

    it('closes with the existing dashboard id when one is picked (no name needed)', () => {
        const { c, ref } = create();
        c.form.controls.target.setValue('ops_overview');
        c.add();
        expect(ref.close).toHaveBeenCalledWith({ existingId: 'ops_overview' });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
