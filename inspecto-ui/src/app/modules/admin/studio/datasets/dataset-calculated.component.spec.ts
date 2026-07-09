import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CalculatedColumn } from './dataset-types';
import { DatasetCalculatedComponent } from './dataset-calculated.component';

const ROWS = [{ amt: 100 }, { amt: 200 }, { amt: 300 }];
const CALCULATED: CalculatedColumn[] = [{ name: 'amt_plus_ten', expr: 'amt + 10' }];

function create(calculated: CalculatedColumn[] = CALCULATED) {
    TestBed.configureTestingModule({
        imports: [DatasetCalculatedComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(DatasetCalculatedComponent);
    fixture.componentRef.setInput('calculated', calculated);
    fixture.componentRef.setInput('sourceName', 'cdr');
    fixture.componentRef.setInput('sampleRows', ROWS);
    fixture.detectChanges();
    return fixture;
}

describe('DatasetCalculatedComponent', () => {
    it('adds a calculated column and emits the updated array', () => {
        const fixture = create([]);
        const c = fixture.componentInstance;
        let emitted: CalculatedColumn[] | undefined;
        c.calculatedChange.subscribe((v) => (emitted = v));
        c.add();
        expect(emitted).toHaveLength(1);
    });

    it('patches a column field and emits the updated array', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        let emitted: CalculatedColumn[] | undefined;
        c.calculatedChange.subscribe((v) => (emitted = v));
        c.patch(0, { expr: 'amt + 20' });
        expect(emitted?.[0].expr).toBe('amt + 20');
        expect(emitted?.[0].name).toBe('amt_plus_ten'); // untouched
    });

    it('removes a calculated column and emits the updated array', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        let emitted: CalculatedColumn[] | undefined;
        c.calculatedChange.subscribe((v) => (emitted = v));
        c.remove(0);
        expect(emitted).toHaveLength(0);
    });

    it('tests an expression against the sample rows via offline AlaSQL — no backend needed', async () => {
        const fixture = create();
        const c = fixture.componentInstance;
        await c.test(0);
        expect(c.testResult()).toEqual({ index: 0, values: [110, 210, 310] }); // amt + 10, 3 sample rows
    });

    it('surfaces a bad expression as an error, not a thrown exception', async () => {
        const fixture = create([{ name: 'bad', expr: '(((' }]); // unbalanced — a syntax error
        const c = fixture.componentInstance;
        await c.test(0);
        expect(c.testResult()?.error).toBeTruthy();
    });

    it('flags a disallowed expression inline (mirrors the backend ExpressionGuard) without blocking Test', () => {
        const fixture = create([{ name: 'leak', expr: "read_parquet('x')" }]);
        const c = fixture.componentInstance;
        expect(c.exprError(c.rows()[0].expr)).toMatch(/not allowed/i);
    });

    it('flags an invalid name inline (mirrors the backend SAFE_IDENT check)', () => {
        const fixture = create([{ name: 'bad-name', expr: 'amt' }]);
        const c = fixture.componentInstance;
        expect(c.nameError(c.rows()[0].name)).toMatch(/letters, digits, underscore/i);
    });

    it('renders the inline error text in the DOM for a disallowed expression', () => {
        // Regression: mat-error only displays when Material's errorState is true (a real Validator on
        // the control), which this plain-ngModel field never sets — so the error must be a plain
        // element, not <mat-error>, or it silently never renders despite the guard function being correct.
        const fixture = create([{ name: 'leak', expr: "(select 1)" }]);
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain("'select' is not allowed in a calculated column.");
        expect(fixture.nativeElement.querySelector('mat-error')).toBeNull();
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
