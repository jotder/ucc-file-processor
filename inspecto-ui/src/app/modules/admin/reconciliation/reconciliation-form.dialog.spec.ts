import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from '../studio/datasets/dataset-types';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { ReconciliationFormDialog, ReconciliationFormResult } from './reconciliation-form.dialog';

const DS = (id: string): Dataset => ({
    id, name: id, kind: 'physical', sourceName: id, physicalRef: id,
    columns: [{ name: 'id', type: 'number', role: 'dimension' }, { name: 'cost_usd', type: 'number', role: 'measure' }],
    measures: [],
    calculated: [],
});

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ReconciliationFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: DatasetsService, useValue: { list: () => of([DS('switch_cdr'), DS('billing_cdr')]) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconciliationFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('ReconciliationFormDialog', () => {
    it('is invalid until name + both datasets + a key column are chosen, then emits the config', () => {
        const { c, ref } = create();
        expect(c.valid()).toBe(false);
        c.name = 'switch vs billing';
        c.leftDataset = 'switch_cdr';
        c.onLeftChange('switch_cdr'); // populates the column pickers
        c.rightDataset = 'billing_cdr';
        c.keyColumns = ['id'];
        c.addCompare();
        c.compareRows()[0].column = 'cost_usd';
        c.compareRows()[0].toleranceType = 'absolute';
        c.compareRows()[0].tolerance = 0.02;
        expect(c.valid()).toBe(true);
        c.submit();
        const result = ref.close.mock.calls[0][0] as ReconciliationFormResult;
        expect(result).toEqual({
            name: 'switch vs billing', leftDataset: 'switch_cdr', rightDataset: 'billing_cdr',
            keyColumns: ['id'], compareColumns: [{ column: 'cost_usd', toleranceType: 'absolute', tolerance: 0.02 }],
        });
    });

    it('offers the left dataset columns as key/compare options after selection', () => {
        const { c } = create();
        c.onLeftChange('switch_cdr');
        expect(c.leftColumns()).toEqual(['id', 'cost_usd']);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
