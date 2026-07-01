import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NamedMeasure } from './dataset-types';
import { DatasetMeasuresComponent } from './dataset-measures.component';

const ROWS = [{ duration_s: 10 }, { duration_s: 20 }, { duration_s: 30 }];
const MEASURES: NamedMeasure[] = [{ id: 'avg_dur', label: 'Avg duration', expression: 'avg(duration_s)' }];

function create(measures: NamedMeasure[] = MEASURES) {
    TestBed.configureTestingModule({
        imports: [DatasetMeasuresComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(DatasetMeasuresComponent);
    fixture.componentRef.setInput('measures', measures);
    fixture.componentRef.setInput('sourceName', 'cdr');
    fixture.componentRef.setInput('sampleRows', ROWS);
    fixture.detectChanges();
    return fixture;
}

describe('DatasetMeasuresComponent', () => {
    it('adds a measure and emits the updated array', () => {
        const fixture = create([]);
        const c = fixture.componentInstance;
        let emitted: NamedMeasure[] | undefined;
        c.measuresChange.subscribe((v) => (emitted = v));
        c.add();
        expect(emitted).toHaveLength(1);
    });

    it('patches a measure field and emits the updated array', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        let emitted: NamedMeasure[] | undefined;
        c.measuresChange.subscribe((v) => (emitted = v));
        c.patch(0, { expression: 'sum(duration_s)' });
        expect(emitted?.[0].expression).toBe('sum(duration_s)');
        expect(emitted?.[0].id).toBe('avg_dur'); // untouched
    });

    it('removes a measure and emits the updated array', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        let emitted: NamedMeasure[] | undefined;
        c.measuresChange.subscribe((v) => (emitted = v));
        c.remove(0);
        expect(emitted).toHaveLength(0);
    });

    it('tests an expression against the sample rows via offline AlaSQL — no backend needed', async () => {
        const fixture = create();
        const c = fixture.componentInstance;
        await c.test(0);
        expect(c.testResult()).toEqual({ index: 0, value: 20 }); // avg(10,20,30) = 20
    });

    it('surfaces a bad expression as an error, not a thrown exception', async () => {
        const fixture = create([{ id: 'bad', label: 'Bad', expression: '(((' }]); // unbalanced — a syntax error
        const c = fixture.componentInstance;
        await c.test(0);
        expect(c.testResult()?.error).toBeTruthy();
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
