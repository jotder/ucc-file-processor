import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
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
        expect(c.form.valid).toBe(false);
        c.form.patchValue({ name: 'switch vs billing', leftDataset: 'switch_cdr', rightDataset: 'billing_cdr' });
        c.onLeftChange('switch_cdr'); // populates the column pickers
        c.form.patchValue({ keyColumns: ['id'] });
        c.addCompare();
        c.compareRowsArray.at(0).patchValue({ column: 'cost_usd', toleranceType: 'absolute', tolerance: 0.02 });
        expect(c.form.valid).toBe(true);
        c.submit();
        const result = ref.close.mock.calls[0][0] as ReconciliationFormResult;
        expect(result).toEqual({
            name: 'switch vs billing', leftDataset: 'switch_cdr', rightDataset: 'billing_cdr',
            keyColumns: ['id'],
            compareColumns: [{ column: 'cost_usd', agg: 'sum', toleranceType: 'absolute', tolerance: 0.02 }],
            bands: { warnPct: 1, breachPct: 2 },
        });
    });

    it('flags the breach threshold when it drops below the warn threshold', () => {
        const { c } = create();
        c.form.patchValue({ warnPct: 5, breachPct: 2 });
        expect(c.form.controls['breachPct'].hasError('belowWarn')).toBe(true);
        c.form.patchValue({ breachPct: 10 });
        expect(c.form.controls['breachPct'].hasError('belowWarn')).toBe(false);
    });

    it('prefills from an existing reconciliation in edit mode and keeps its bands', () => {
        const ref = { close: vi.fn() };
        TestBed.configureTestingModule({
            imports: [ReconciliationFormDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MatDialogRef, useValue: ref },
                { provide: DatasetsService, useValue: { list: () => of([DS('switch_cdr'), DS('billing_cdr')]) } },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: {
                        recon: {
                            id: 'r1', name: 'edit me', leftDataset: 'switch_cdr', rightDataset: 'billing_cdr',
                            keyColumns: ['id'],
                            compareColumns: [{ column: 'cost_usd', agg: 'count', toleranceType: 'percent', tolerance: 0.5 }],
                            bands: { warnPct: 0.5, breachPct: 3 }, breaks: [], lastRunAt: null,
                        },
                    },
                },
            ],
        });
        const fixture = TestBed.createComponent(ReconciliationFormDialog);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.editing).toBe(true);
        expect(c.form.controls['name'].value).toBe('edit me');
        expect(c.form.controls['keyColumns'].value).toEqual(['id']);
        expect(c.compareRowsArray.at(0).get('agg')?.value).toBe('count');
        expect(c.form.controls['warnPct'].value).toBe(0.5);
        expect(c.form.controls['breachPct'].value).toBe(3);
        expect(c.form.valid).toBe(true);
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
