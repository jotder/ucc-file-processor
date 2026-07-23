import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ExchangeService, SessionService, SpacesService } from 'app/inspecto/api';
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
    calculated: [],
};

function create(datasets: Dataset[] = [D1], opts: { canShare?: boolean; offerResult?: { description: string } } = {}) {
    const remove = vi.fn(() => of(null));
    const offer = vi.fn(() => of({} as never));
    const dialog = { open: () => ({ afterClosed: () => of(opts.offerResult) }) };
    TestBed.configureTestingModule({
        imports: [DatasetsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: DatasetsService, useValue: { list: () => of(datasets), remove } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            { provide: MatDialog, useValue: dialog },
            // authMode 'none' keeps LensService on the honor system (R2 grant checks bypassed).
            { provide: SessionService, useValue: { exchangeEnabled: () => opts.canShare ?? false, authMode: () => 'none', capabilities: () => [] } },
            { provide: ExchangeService, useValue: { offer } },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'default' } },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    return { fixture: TestBed.createComponent(DatasetsComponent), remove, offer };
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

    it('offers a dataset for sharing when the dialog is confirmed', () => {
        const { fixture, offer } = create([D1], { canShare: true, offerResult: { description: 'ref rates' } });
        fixture.detectChanges();
        expect(fixture.componentInstance.canShare()).toBe(true);
        fixture.componentInstance.offer(D1);
        expect(offer).toHaveBeenCalledWith({ kind: 'dataset', owner: 'default', item: 'cdr_view', description: 'ref rates' });
    });

    it('does not offer when the share dialog is cancelled', () => {
        const { fixture, offer } = create([D1], { canShare: true, offerResult: undefined });
        fixture.detectChanges();
        fixture.componentInstance.offer(D1);
        expect(offer).not.toHaveBeenCalled();
    });

    it('flags a dataset bound to a shared ref with its owner', () => {
        const shared: Dataset = { ...D1, id: 'fx_local', kind: 'physical', physicalRef: 'shared/analytics-hub/fx_rates_daily' };
        const { fixture } = create([shared, D1]);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.sharedOwner(shared)).toBe('analytics-hub');
        expect(c.sharedOwner(D1)).toBeNull();
    });

    it('renders the empty state with no a11y violations', async () => {
        const { fixture } = create([]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
