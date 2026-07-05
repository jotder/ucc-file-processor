import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GeoSource } from 'app/inspecto/geo';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { GeoMapComponent } from './geo-map.component';
import { GeoSourcesService, ProjectedGeo } from './geo-projection';
import { GeoMapService, GeoMapView } from './geo-map.service';

const DS: Dataset = { id: 'towers-ds', name: 'Towers', kind: 'physical', sourceName: 'towers', columns: [], measures: [] };

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

function create(opts: { fail?: boolean; views?: GeoMapView[] } = {}) {
    const queried: unknown[] = [];
    const fakeSource: GeoSource = {
        id: 'dataset',
        label: 'Locations (from a Dataset)',
        query: (q) => {
            queried.push(q);
            return opts.fail ? Promise.reject(new Error('bad mapping')) : Promise.resolve(GEO);
        },
    };
    const save = vi.fn((v: GeoMapView) => of(v));
    TestBed.configureTestingModule({
        imports: [GeoMapComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GeoSourcesService, useValue: { sources: [fakeSource] } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: GeoMapService, useValue: { list: () => of(opts.views ?? []), save } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
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

    it('reports a failed query inline', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.geo()).toBeNull();
        expect(c.loadError()).toBe('bad mapping');
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
