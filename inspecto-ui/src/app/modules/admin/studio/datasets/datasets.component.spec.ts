import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from './dataset-types';
import { DatasetsService } from './datasets.service';
import { DatasetsComponent } from './datasets.component';

const D1: Dataset = {
    id: 'cdr_view',
    name: 'cdr_view',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [{ name: 'duration_s', type: 'number', role: 'measure' }],
    measures: [],
};

function create(datasets: Dataset[] = [D1]) {
    const remove = vi.fn(() => of(null));
    TestBed.configureTestingModule({
        imports: [DatasetsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: DatasetsService, useValue: { list: () => of(datasets), remove } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
        ],
    });
    return { fixture: TestBed.createComponent(DatasetsComponent), remove };
}

describe('DatasetsComponent', () => {
    it('loads datasets on init', () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.datasets().length).toBe(1);
        expect(fixture.componentInstance.visibleDatasets()[0].id).toBe('cdr_view');
    });

    it('filters by id / kind / source', () => {
        const { fixture } = create();
        fixture.detectChanges();
        fixture.componentInstance.filterText.set('nomatch');
        expect(fixture.componentInstance.visibleDatasets().length).toBe(0);
        fixture.componentInstance.filterText.set('virtual');
        expect(fixture.componentInstance.visibleDatasets().length).toBe(1);
    });

    it('deletes after confirmation', async () => {
        const { fixture, remove } = create();
        fixture.detectChanges();
        await fixture.componentInstance.remove(D1);
        expect(remove).toHaveBeenCalledWith('cdr_view');
    });

    it('renders the empty state with no a11y violations', async () => {
        const { fixture } = create([]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
