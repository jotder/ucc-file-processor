import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DatasetColumn } from './dataset-types';
import { DatasetColumnsComponent } from './dataset-columns.component';

const COLS: DatasetColumn[] = [
    { name: 'duration_s', type: 'number', role: 'measure' },
    { name: 'tariff', type: 'string', role: 'dimension' },
];

function create() {
    TestBed.configureTestingModule({
        imports: [DatasetColumnsComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(DatasetColumnsComponent);
    fixture.componentRef.setInput('columns', COLS);
    fixture.detectChanges();
    return fixture;
}

describe('DatasetColumnsComponent', () => {
    it('patches a column role and emits the updated array', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        let emitted: DatasetColumn[] | undefined;
        c.columnsChange.subscribe((v) => (emitted = v));
        c.patch('tariff', { role: 'temporal' });
        expect(emitted?.find((x) => x.name === 'tariff')?.role).toBe('temporal');
        // other columns untouched
        expect(emitted?.find((x) => x.name === 'duration_s')?.role).toBe('measure');
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
