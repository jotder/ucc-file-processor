import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { Widget } from './widget-types';
import { WidgetsService } from './widgets.service';
import { WidgetsComponent } from './widgets.component';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';

const W1: Widget = { id: 'dur_by_tariff', name: 'dur_by_tariff', datasetId: 'cdr_sample', vizType: 'bar', controls: {}, tags: ['ops'] };
const W2: Widget = { id: 'other', name: 'other', datasetId: 'cdr_sample', vizType: 'line', controls: {}, tags: ['billing'] };
const DS: Dataset = { id: 'cdr_sample', name: 'cdr_sample', kind: 'virtual', sourceName: 'cdr', columns: [], measures: [] };

function create(widgets: Widget[] = [W1], datasets: Dataset[] = [DS]) {
    registerBuiltinViz();
    const remove = vi.fn(() => of(null));
    TestBed.configureTestingModule({
        imports: [WidgetsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: WidgetsService, useValue: { list: () => of(widgets), remove } },
            { provide: DatasetsService, useValue: { list: () => of(datasets) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
        ],
    });
    return { fixture: TestBed.createComponent(WidgetsComponent), remove };
}

describe('WidgetsComponent', () => {
    it('loads widgets on init and filters them', () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.visibleWidgets()).toHaveLength(1);
        fixture.componentInstance.filterText.set('line');
        expect(fixture.componentInstance.visibleWidgets()).toHaveLength(0);
    });

    it('filters by tag, and toggling the same tag again clears the filter', () => {
        const { fixture } = create([W1, W2]);
        fixture.detectChanges();
        expect(fixture.componentInstance.allTags()).toEqual(['billing', 'ops']);
        fixture.componentInstance.toggleTag('ops');
        expect(fixture.componentInstance.visibleWidgets().map((w) => w.id)).toEqual(['dur_by_tariff']);
        fixture.componentInstance.toggleTag('ops');
        expect(fixture.componentInstance.visibleWidgets()).toHaveLength(2);
    });

    it('deletes after confirmation', async () => {
        const { fixture, remove } = create();
        fixture.detectChanges();
        await fixture.componentInstance.remove(W1);
        expect(remove).toHaveBeenCalledWith('dur_by_tariff');
    });

    it('renders the empty state with no a11y violations', async () => {
        const { fixture } = create([]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders a live thumbnail per card with no a11y violations', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
