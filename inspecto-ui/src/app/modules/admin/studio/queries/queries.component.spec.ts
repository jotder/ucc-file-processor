import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Query } from './query-types';
import { QueriesService } from './queries.service';
import { QueriesComponent } from './queries.component';

const DS: Dataset = {
    id: 'cdr_sample', name: 'cdr_sample', kind: 'virtual', sourceName: 'cdr',
    columns: [{ name: 'cost_usd', type: 'number', role: 'measure' }], measures: [], calculated: [],
};
const Q: Query = { id: 'recent', name: 'recent', type: 'sql', datasetId: 'cdr_sample', sourceName: 'cdr', text: 'SELECT * FROM cdr', parameters: [] };

function create(queries: Query[] = [Q]) {
    const save = vi.fn((q: Query) => of(q));
    const remove = vi.fn(() => of(null));
    TestBed.configureTestingModule({
        imports: [QueriesComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: QueriesService, useValue: { list: () => of(queries), get: () => of(Q), save, remove } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
        ],
    });
    return { fixture: TestBed.createComponent(QueriesComponent), save, remove };
}

describe('QueriesComponent (R3)', () => {
    it('loads queries and datasets on init', () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.queries().length).toBe(1);
        expect(fixture.componentInstance.datasets()[0].id).toBe('cdr_sample');
    });

    it('opens a blank editor on New, and closes on Cancel', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        expect(c.editing()).toBe(true);
        expect(c.editingExisting()).toBe(false);
        c.cancel();
        expect(c.editing()).toBe(false);
    });

    it('detects user-declared $params from the SQL (built-ins excluded)', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.controls.text.setValue('WHERE t >= $day(-7) AND region = $region');
        expect(c.userParamNames()).toEqual(['region']);
        expect(c.builtinTokens()).toEqual(['$day(-7)']);
    });

    it('editing an existing query disables the name and seeds its param defaults', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.editQuery({ ...Q, parameters: [{ name: 'region', type: 'string', default: 'APAC' }] });
        expect(c.editingExisting()).toBe(true);
        expect(c.form.controls.name.disabled).toBe(true);
        expect(c.paramDefaults()['region']).toBe('APAC');
    });

    it('saves a query built from the form', () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.newQuery();
        c.form.patchValue({ name: 'new_q', datasetId: 'cdr_sample', text: 'SELECT 1 FROM cdr' });
        c.save();
        expect(save).toHaveBeenCalled();
        expect(save.mock.calls[0][0]).toMatchObject({ id: 'new_q', datasetId: 'cdr_sample', type: 'sql' });
    });

    it('deletes after confirmation', async () => {
        const { fixture, remove } = create();
        fixture.detectChanges();
        await fixture.componentInstance.remove(Q);
        expect(remove).toHaveBeenCalledWith('recent');
    });

    it('renders the library with no a11y violations', async () => {
        const { fixture } = create([]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
