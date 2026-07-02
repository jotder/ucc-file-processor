import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { ConditionGroup } from 'app/inspecto/query';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DashboardFilterBarComponent } from './dashboard-filter-bar.component';

const FILTER: ConditionGroup = {
    kind: 'group',
    op: 'AND',
    items: [
        { kind: 'condition', field: 'tariff', operator: '=', value: 'premium' },
        { kind: 'condition', field: 'cell_id', operator: '=', value: 'CELL-101' }, // not exposed → no chip
        { kind: 'condition', field: 'tariff', operator: '!=', value: 'standard' }, // non-equality → no chip
    ],
};

function create(fields: string[] = ['tariff'], filter: ConditionGroup = FILTER) {
    TestBed.configureTestingModule({
        imports: [DashboardFilterBarComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(DashboardFilterBarComponent);
    fixture.componentRef.setInput('fields', fields);
    fixture.componentRef.setInput('values', { tariff: ['premium', 'standard'] });
    fixture.componentRef.setInput('filter', filter);
    fixture.detectChanges();
    return fixture;
}

describe('DashboardFilterBarComponent', () => {
    it('chips only the exposed equality conditions', () => {
        const fixture = create();
        const chips = fixture.componentInstance.activeConditions();
        expect(chips).toHaveLength(1);
        expect(chips[0]).toMatchObject({ field: 'tariff', value: 'premium' });
    });

    it('clicking a chip emits a toggle for that field/value', () => {
        const fixture = create();
        const spy = vi.fn();
        fixture.componentInstance.toggle.subscribe(spy);
        const chip = fixture.nativeElement.querySelector('button[aria-label="Remove filter tariff = premium"]') as HTMLButtonElement;
        chip.click();
        expect(spy).toHaveBeenCalledWith({ field: 'tariff', value: 'premium' });
    });

    it('picking a value emits a toggle; empty pick is ignored', () => {
        const fixture = create();
        const spy = vi.fn();
        fixture.componentInstance.toggle.subscribe(spy);
        fixture.componentInstance.onPick('tariff', 'standard');
        fixture.componentInstance.onPick('tariff', null);
        expect(spy).toHaveBeenCalledTimes(1);
        expect(spy).toHaveBeenCalledWith({ field: 'tariff', value: 'standard' });
    });

    it('renders nothing when no fields are exposed', () => {
        const fixture = create([]);
        expect(fixture.nativeElement.textContent.trim()).toBe('');
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create().nativeElement);
    });
});
