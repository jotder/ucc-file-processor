import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { ExchangeService, SessionService, SpacesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { AddToDashboardResult } from './add-to-dashboard.dialog';
import { Widget } from './widget-types';
import { WidgetsService } from './widgets.service';
import { WidgetsComponent } from './widgets.component';
import { Dashboard } from '../dashboards/dashboard-types';
import { DashboardsService } from '../dashboards/dashboards.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';

const W1: Widget = { id: 'dur_by_tariff', name: 'dur_by_tariff', datasetId: 'cdr_sample', vizType: 'bar', controls: {}, tags: ['ops'] };
const W2: Widget = { id: 'other', name: 'other', datasetId: 'cdr_sample', vizType: 'line', controls: {}, tags: ['billing'] };
const DS: Dataset = { id: 'cdr_sample', name: 'cdr_sample', kind: 'virtual', sourceName: 'cdr', columns: [], measures: [], calculated: [] };
const D1: Dashboard = { id: 'd1', name: 'd1', tiles: [{ widgetId: 'other', span: 1 }], filter: null };

function create(
    widgets: Widget[] = [W1],
    datasets: Dataset[] = [DS],
    dialogResult?: AddToDashboardResult | { description: string },
    opts: { canShare?: boolean } = {},
) {
    registerBuiltinViz();
    const remove = vi.fn(() => of(null));
    const saveDashboard = vi.fn((d: Dashboard) => of(d));
    const offer = vi.fn(() => of({} as never));
    const dialog = { open: () => ({ afterClosed: () => of(dialogResult) }) };
    TestBed.configureTestingModule({
        imports: [WidgetsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: WidgetsService, useValue: { list: () => of(widgets), remove } },
            { provide: DatasetsService, useValue: { list: () => of(datasets) } },
            { provide: DashboardsService, useValue: { list: () => of([D1]), get: () => of({ ...D1, tiles: [...D1.tiles] }), save: saveDashboard } },
            { provide: MatDialog, useValue: dialog },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            // authMode 'none' keeps LensService on the honor system (R2 grant checks bypassed).
            { provide: SessionService, useValue: { exchangeEnabled: () => opts.canShare ?? false, authMode: () => 'none', capabilities: () => [] } },
            { provide: ExchangeService, useValue: { offer } },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'default' } },
        ],
    });
    // A component-level MatDialog provider shadows the root-level test double — override wins at any level.
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    return { fixture: TestBed.createComponent(WidgetsComponent), remove, saveDashboard, navigate, offer };
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

    it('filters by viz type, and toggling the same type again clears the filter', () => {
        const { fixture } = create([W1, W2]);
        fixture.detectChanges();
        expect(fixture.componentInstance.allTypes()).toEqual(['bar', 'line']);
        fixture.componentInstance.toggleType('bar');
        expect(fixture.componentInstance.visibleWidgets().map((w) => w.id)).toEqual(['dur_by_tariff']);
        fixture.componentInstance.toggleType('bar');
        expect(fixture.componentInstance.visibleWidgets()).toHaveLength(2);
    });

    it('places the widget on a NEW dashboard and navigates to the Dashboard Builder', () => {
        const { fixture, saveDashboard, navigate } = create([W1], [DS], { newName: 'fraud_overview' });
        fixture.detectChanges();
        fixture.componentInstance.addToDashboard(W1);
        expect(saveDashboard).toHaveBeenCalledWith({
            id: 'fraud_overview',
            name: 'fraud_overview',
            tiles: [{ widgetId: 'dur_by_tariff', span: 1 }],
            filter: null,
        });
        expect(navigate).toHaveBeenCalledWith(['/studio/dashboards', 'fraud_overview']);
    });

    it('appends the widget to an EXISTING dashboard', () => {
        const { fixture, saveDashboard, navigate } = create([W1], [DS], { existingId: 'd1' });
        fixture.detectChanges();
        fixture.componentInstance.addToDashboard(W1);
        expect(saveDashboard).toHaveBeenCalledTimes(1);
        expect(saveDashboard.mock.calls[0][0].tiles).toEqual([
            { widgetId: 'other', span: 1 },
            { widgetId: 'dur_by_tariff', span: 1 },
        ]);
        expect(navigate).toHaveBeenCalledWith(['/studio/dashboards', 'd1']);
    });

    it('does nothing when the add-to-dashboard dialog is cancelled', () => {
        const { fixture, saveDashboard } = create([W1], [DS], undefined);
        fixture.detectChanges();
        fixture.componentInstance.addToDashboard(W1);
        expect(saveDashboard).not.toHaveBeenCalled();
    });

    it('deletes after confirmation', async () => {
        const { fixture, remove } = create();
        fixture.detectChanges();
        await fixture.componentInstance.remove(W1);
        expect(remove).toHaveBeenCalledWith('dur_by_tariff');
    });

    it('flags a widget whose bound dataset is a shared ref', () => {
        const sharedDs: Dataset = { ...DS, physicalRef: 'shared/analytics-hub/fx_rates_daily' };
        const { fixture } = create([W1], [sharedDs]);
        fixture.detectChanges();
        expect(fixture.componentInstance.sharedOwner(W1)).toBe('analytics-hub');
    });

    it('does not flag a widget whose dataset is local', () => {
        const { fixture } = create([W1], [DS]);
        fixture.detectChanges();
        expect(fixture.componentInstance.sharedOwner(W1)).toBeNull();
    });

    it('offers a widget for sharing when the dialog is confirmed', () => {
        const { fixture, offer } = create([W1], [DS], { description: 'ops chart' }, { canShare: true });
        fixture.detectChanges();
        expect(fixture.componentInstance.canShare()).toBe(true);
        fixture.componentInstance.offer(W1);
        expect(offer).toHaveBeenCalledWith({ kind: 'widget', owner: 'default', item: 'dur_by_tariff', description: 'ops chart' });
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
