import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { GeoSettingsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GeoSource } from 'app/inspecto/geo';
import { ElementDetailData } from 'app/inspecto/investigation';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { GeoMapComponent } from './geo-map.component';
import { GeoSourcesService, ProjectedGeo } from './geo-projection';
import { GeoMapService, GeoMapView } from './geo-map.service';

const DS: Dataset = { id: 'towers-ds', name: 'Towers', kind: 'physical', sourceName: 'towers', columns: [], measures: [], calculated: [] };

const GEO: ProjectedGeo = {
    points: [
        { id: 'pt:0', lat: 23.81, lon: 90.41, kind: 'tower', label: 'T1', attrs: { site: 'T1' } },
        { id: 'pt:1', lat: 23.79, lon: 90.4, kind: 'tower', label: 'T2', attrs: { site: 'T2' } },
        { id: 'pt:2', lat: 51.5, lon: -0.12, kind: 'device', label: 'D1', attrs: { site: 'D1' } },
    ],
    routes: [],
    truncated: false,
    skipped: 2,
};

const ROUTES_GEO: ProjectedGeo = {
    points: [
        { id: 'ep:Dhaka', lat: 23.81, lon: 90.41, kind: 'place', label: 'Dhaka', time: 100 },
        { id: 'ep:Dubai', lat: 25.2, lon: 55.27, kind: 'place', label: 'Dubai', time: 200 },
    ],
    routes: [{ id: 'r1', from: 'ep:Dhaka', to: 'ep:Dubai', kind: 'hundi', label: 'hundi · 2', weight: 2, time: 150 }],
    truncated: false,
    skipped: 0,
};

function create(opts: { fail?: boolean; views?: GeoMapView[]; geo?: ProjectedGeo; queryParams?: Record<string, string> } = {}) {
    const queried: unknown[] = [];
    const fakeSource: GeoSource = {
        id: 'dataset',
        label: 'Locations (from a Dataset)',
        query: (q) => {
            queried.push(q);
            return opts.fail ? Promise.reject(new Error('bad mapping')) : Promise.resolve(opts.geo ?? GEO);
        },
    };
    const fakeRouteSource: GeoSource = {
        id: 'od-routes',
        label: 'Routes (origin → destination)',
        query: (q) => {
            queried.push(q);
            return Promise.resolve(ROUTES_GEO);
        },
    };
    const save = vi.fn((v: GeoMapView) => of(v));
    TestBed.configureTestingModule({
        imports: [GeoMapComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GeoSourcesService, useValue: { sources: [fakeSource, fakeRouteSource] } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: GeoMapService, useValue: { list: () => of(opts.views ?? []), save } },
            { provide: GeoSettingsService, useValue: { get: () => of({ tileServerUrl: null }) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined, info: () => undefined } },
            {
                provide: ActivatedRoute,
                useValue: { snapshot: { queryParamMap: convertToParamMap(opts.queryParams ?? {}) } },
            },
        ],
    });
    return { fixture: TestBed.createComponent(GeoMapComponent), queried, save };
}

// After a load the state is driven directly — like the G6 hosts, MapLibre can't
// instantiate in jsdom (the map host's WebGL guard keeps it unmounted).
async function runQuery(fixture: ReturnType<typeof create>['fixture']): Promise<void> {
    const c = fixture.componentInstance;
    c.queryForm.patchValue({ datasetId: 'towers-ds', latCol: 'lat', lonCol: 'lon' });
    await c.run();
}

describe('GeoMapComponent', () => {
    it('renders the empty state, is a11y-clean, and blocks a run without a mapping', async () => {
        const { fixture, queried } = create();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No map yet');
        await fixture.componentInstance.run(); // nothing picked yet → invalid form, no query
        expect(queried).toHaveLength(0);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('runs the query, surfaces skipped rows, and search/kind filters drive emphasis + rows', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.geo()).toEqual(GEO);
        expect(c.geo()?.skipped).toBe(2);
        expect(c.pointKinds()).toEqual(['device', 'tower']);
        expect(c.queryOpen()).toBe(false); // auto-collapsed after the run

        c.onSearch('t1');
        expect(c.emphasis()?.pointIds).toEqual(['pt:0']);
        expect(c.rows().map((r) => r.label)).toEqual(['T1']);

        c.onSearch('');
        c.toggleKind('tower', true);
        expect(c.displayed()?.points.map((p) => p.id)).toEqual(['pt:0', 'pt:1']);

        c.clearInvestigation();
        expect(c.displayed()?.points).toHaveLength(3);
        expect(c.rows()).toHaveLength(3);
    });

    it('find-place geocodes via the seam and populates candidates (blank clears them)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.onFindPlace('dhaka');
        await Promise.resolve(); // the geocode promise resolves synchronously (offline table)
        expect(c.placeResults().map((r) => r.name)).toContain('Dhaka');
        c.onFindPlace('');
        expect(c.placeResults()).toEqual([]);
    });

    it('resolves an incoming investigation pivot against the loaded points (ui-design-review R8)', async () => {
        const geoWithRef: ProjectedGeo = {
            points: [...GEO.points, { id: 'pt:case', lat: 10, lon: 20, kind: 'tower', label: 'T3', attrs: { caseId: 'case-1' } }],
            routes: [],
            truncated: false,
            skipped: 0,
        };
        const { fixture } = create({ geo: geoWithRef, queryParams: { pivotId: 'case-1', pivotType: 'CASE' } });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        await runQuery(fixture);
        expect(c.selectedId()).toBe('pt:case');
    });

    it('toasts when the pivoted-in record is not in the loaded points', async () => {
        const { fixture } = create({ queryParams: { pivotId: 'case-missing', pivotType: 'CASE' } });
        fixture.detectChanges();
        const toastr = TestBed.inject(ToastrService);
        const infoSpy = vi.spyOn(toastr, 'info');
        await runQuery(fixture);
        expect(infoSpy).toHaveBeenCalled();
        expect(fixture.componentInstance.selectedId()).toBeNull();
    });

    it('reports a failed query inline', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.geo()).toBeNull();
        expect(c.loadError()).toBe('bad mapping');
    });

    it('runs the od-routes source: weighted routes, time extent, and the timeline filter', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.sourceId.set('od-routes');
        c.queryForm.patchValue({ datasetId: 'towers-ds', fromLatCol: 'fa', fromLonCol: 'fo', toLatCol: 'ta', toLonCol: 'to' });
        await c.run();
        expect(c.geo()?.routes).toHaveLength(1);
        expect(c.querySummary()).toContain('→');
        expect(c.extent()).toEqual([100, 200]);

        c.setTimeTo(120); // window [100, 120] keeps Dhaka, drops Dubai and the route
        expect(c.displayed()?.points.map((p) => p.label)).toEqual(['Dhaka']);
        expect(c.displayed()?.routes).toHaveLength(0);
        c.clearInvestigation();
        expect(c.timeRange()).toBeNull();
        expect(c.displayed()?.routes).toHaveLength(1);
    });

    it('a toolbox focus request drives the map emphasis; clearInvestigation clears it', async () => {
        // The geo-intelligence tools themselves live in GeoAnalysisToolboxComponent (its own spec);
        // here we cover the host seam: a focus event → resultEmphasis → the `emphasis` computed.
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.focusResult({ pointIds: ['pt:0', 'pt:1'], lat: 23.81, lon: 90.41 });
        expect(c.emphasis()?.pointIds).toEqual(['pt:0', 'pt:1']);

        c.clearInvestigation();
        expect(c.emphasis()).toBeNull();
    });

    it('playback sweeps the time window across the extent and stops at the end', async () => {
        vi.useFakeTimers();
        try {
            const { fixture } = create();
            fixture.detectChanges();
            await runQuery(fixture);
            const c = fixture.componentInstance;
            c.geo.set({
                points: [
                    { id: 'p1', lat: 1, lon: 1, kind: 'x', label: 'P', time: 0 },
                    { id: 'p2', lat: 2, lon: 2, kind: 'x', label: 'P', time: 30_000 },
                ],
                routes: [], truncated: false, skipped: 0,
            });
            c.togglePlay();
            expect(c.playing()).toBe(true);
            expect(c.timeRange()).toEqual([0, 0]);
            vi.advanceTimersByTime(400 * 5);
            expect(c.timeRange()![1]).toBeGreaterThan(0);
            vi.advanceTimersByTime(400 * 40);
            expect(c.playing()).toBe(false); // reached the end and stopped
            expect(c.timeRange()![1]).toBe(30_000);
        } finally {
            vi.useRealTimers();
        }
    });

    it('investigation tools: measure, radius search, polygon filter and notes', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        // measure: Dhaka → London along two clicks
        c.setTool('measure');
        c.onMapClick({ lat: 23.81, lon: 90.41 });
        c.onMapClick({ lat: 51.5, lon: -0.12 });
        expect(c.measureTotalM()).toBeGreaterThan(7_900_000);
        expect(c.overlay().features.some((f) => f.geometry.type === 'LineString')).toBe(true);

        // radius search centered on pt:0 (pt:1 is ~2.4 km away, London far outside)
        c.setTool('radius');
        c.searchRadiusM.set(5000);
        c.onMapClick({ lat: 23.81, lon: 90.41 });
        expect(c.radiusHits().map((h) => h.point.id)).toEqual(['pt:0', 'pt:1']);
        expect(c.overlay().features.some((f) => f.geometry.type === 'Polygon')).toBe(true);

        // polygon filter: a box around Dhaka keeps 2 of 3 points
        c.clearTools();
        c.setTool('polygon');
        c.onMapClick({ lat: 23, lon: 90 });
        c.onMapClick({ lat: 23, lon: 91 });
        c.onMapClick({ lat: 24.5, lon: 91 });
        c.onMapClick({ lat: 24.5, lon: 90 });
        c.closePolygon();
        expect(c.displayed()?.points.map((p) => p.id)).toEqual(['pt:0', 'pt:1']);
        expect(c.activeTool()).toBeNull();

        // notes: need text; ignored while a non-note tool owns the canvas
        c.setTool('note');
        c.onMapClick({ lat: 23.81, lon: 90.41 }); // no text yet → ignored
        expect(c.notes()).toHaveLength(0);
        c.noteText.set('meeting point');
        c.onMapClick({ lat: 23.81, lon: 90.41 });
        expect(c.notes()).toHaveLength(1);
        expect(c.overlay().features.some((f) => f.properties?.['label'] === 'meeting point')).toBe(true);

        // clearInvestigation drops tool artifacts but keeps annotations
        c.clearInvestigation();
        expect(c.polygonFilter()).toBeNull();
        expect(c.displayed()?.points).toHaveLength(3);
        expect(c.notes()).toHaveLength(1);
    });

    it('persists notes with a saved view and blocks point details while a tool is active', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.setTool('note');
        c.noteText.set('drop site');
        c.onMapClick({ lat: 23.79, lon: 90.4 });
        c.setTool(null);
        c.saveForm.patchValue({ name: 'Annotated' });
        await c.saveView();
        expect(save.mock.calls[0][0].notes).toHaveLength(1);
        expect(save.mock.calls[0][0].notes![0].text).toBe('drop site');

        // while a tool is active, canvas point clicks don't open the detail dialog
        c.setTool('measure');
        c.onPointClick('pt:0');
        expect(c.selectedId()).toBeNull();
    });

    it('offers no pivot for a plain point, but resolves + navigates to a Case when the row carries a caseId (R8)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        // The component imports MatDialogModule, so ITS MatDialog lives in its standalone environment
        // injector — resolve through the component's injector, not TestBed's root (different instance).
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const router = TestBed.inject(Router);
        const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

        const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
        const dataOf = (call: number): ElementDetailData => (openSpy.mock.calls[call][1] as { data: ElementDetailData }).data;
        c.onPointClick('pt:0'); // attrs: { site: 'T1' } — no case/incident reference
        expect(dataOf(0).objectRef).toBeUndefined();
        expect(dataOf(0).pivotViews).toBeUndefined();

        c.geo()!.points[0].attrs = { ...c.geo()!.points[0].attrs, caseId: 'case-9' };
        openSpy.mockReturnValue({ afterClosed: () => of('open-record') } as never);
        c.onPointClick('pt:0');
        expect(dataOf(1).objectRef).toEqual({ id: 'case-9', type: 'CASE' });
        expect(dataOf(1).pivotViews).toEqual(['graph']);
        expect(navigate).toHaveBeenCalledWith(['/cases', 'case-9']);
    });

    it('captures display mode with a saved view and restores a route view', async () => {
        const routeView: GeoMapView = {
            id: 'corridors', name: 'Corridors', sourceId: 'od-routes', display: 'heatmap',
            query: { routes: { datasetId: 'towers-ds', fromLatCol: 'fa', fromLonCol: 'fo', toLatCol: 'ta', toLonCol: 'to', kindCol: 'ch' } },
        };
        const { fixture, save } = create({ views: [routeView] });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        await runQuery(fixture);
        c.displayMode.set('heatmap');
        c.saveForm.patchValue({ name: 'Heat view' });
        await c.saveView();
        expect(save).toHaveBeenCalledOnce();
        expect(save.mock.calls[0][0].display).toBe('heatmap');

        await c.loadView(routeView);
        expect(c.sourceId()).toBe('od-routes');
        expect(c.displayMode()).toBe('heatmap');
        expect(c.queryForm.getRawValue().kindCol).toBe('ch');
        expect(c.geo()?.routes).toHaveLength(1);
    });

    it('saves the current run as a view (unique name enforced) and loads it back', async () => {
        const existing: GeoMapView = {
            id: 'dhaka', name: 'Dhaka', sourceId: 'dataset',
            query: { projection: { datasetId: 'towers-ds', latCol: 'lat', lonCol: 'lon', kindCol: 'type' } },
        };
        const { fixture, save, queried } = create({ views: [existing] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.saveForm.patchValue({ name: 'Dhaka' });
        expect(c.saveForm.controls.name.hasError('duplicate')).toBe(true);
        await c.saveView();
        expect(save).not.toHaveBeenCalled();

        c.saveForm.patchValue({ name: 'Dhaka towers' });
        await c.saveView();
        expect(save).toHaveBeenCalledOnce();
        expect(c.views().map((v) => v.name)).toContain('Dhaka towers');

        await c.loadView(existing);
        expect(c.queryForm.getRawValue().kindCol).toBe('type');
        expect(queried.length).toBeGreaterThanOrEqual(2);
    });
});
