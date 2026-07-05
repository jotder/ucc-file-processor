import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { ComponentsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { WidgetsService } from './widgets.service';
import { ExploreComponent } from './explore.component';

const DS: Dataset = {
    id: 'cdr_sample',
    name: 'cdr_sample',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [
        { name: 'tariff', type: 'string', role: 'dimension' },
        { name: 'duration_s', type: 'number', role: 'measure' },
    ],
    measures: [],
};

function create() {
    TestBed.configureTestingModule({
        imports: [ExploreComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: DatasetsService, useValue: { list: () => of([DS]), get: () => of(DS) } },
            { provide: WidgetsService, useValue: { list: () => of([]), get: () => of(null), save: () => of(null) } },
            {
                provide: ComponentsService,
                useValue: {
                    list: () =>
                        of([{ type: 'geo-map-view', name: 'dhaka-network', ref: '', content: { name: 'Example — Dhaka cell network' } }]),
                },
            },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    return TestBed.createComponent(ExploreComponent);
}

describe('ExploreComponent', () => {
    it('loads datasets on init', () => {
        const fixture = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.datasets()).toHaveLength(1);
    });

    it('selecting a dataset picks a recommended viz and auto-assigns channels', () => {
        const c = create().componentInstance;
        c.onSelectDataset('cdr_sample');
        expect(c.dataset()?.id).toBe('cdr_sample');
        expect(c.vizType()).toBeTruthy();
        // a measure field got mapped onto some channel
        const mapped = Object.values(c.controls()).some((vals) => vals?.some((v) => v.field === 'duration_s'));
        expect(mapped).toBe(true);
    });

    it('selecting a view-bound plugin swaps the field mapper for the saved-view picker', () => {
        const c = create().componentInstance;
        c.setVizType('geo-map');
        expect(c.viewBound()).toBe(true);
        expect(c.controls()).toEqual({});
        expect(c.savedViews()).toEqual([{ id: 'dhaka-network', name: 'Example — Dhaka cell network' }]);
    });

    it('renders the initial (no dataset) state with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
