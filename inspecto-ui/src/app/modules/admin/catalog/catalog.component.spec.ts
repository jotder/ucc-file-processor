import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { CatalogService, ExchangeService, MetadataGraph, MetadataNode, PipelinesService, SessionService, SpacesService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentsDataProvider } from './components-data-provider';
import { CatalogComponent } from './catalog.component';

const TABLE: MetadataNode = { id: 'tbl/cdr', kind: 'TABLE', label: 'cdr' };
const STREAM: MetadataNode = {
    id: 'stream:orders', kind: 'STREAM', label: 'orders',
    attrs: { connector: 'local', pipeline: 'orders', active: false },
};
const GRAPH: MetadataGraph = { nodes: [TABLE], edges: [] };
const EMPTY_GRAPH: MetadataGraph = { nodes: [], edges: [] };

function create(overrides: Partial<CatalogService> = {}, queryParams: Record<string, string> = {}) {
    const api = {
        tables: () => of([TABLE]),
        streams: () => of([]),
        references: () => of([]),
        kpis: () => of({ kpis: [] }),
        graph: () => of(GRAPH),
        ...overrides,
    } as unknown as CatalogService;
    TestBed.configureTestingModule({
        imports: [CatalogComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } },
            { provide: CatalogService, useValue: api },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            // RegistryComponent is embedded (not lazy) for the "usage" tab.
            { provide: ComponentsDataProvider, useValue: { list: () => Promise.resolve([]) } },
            { provide: PipelinesService, useValue: { authoredList: () => of([]), authoredRaw: () => of(undefined) } },
            // The Exchange tabs gate on bootstrap.features.exchange (SharingComponent is embedded).
            { provide: SessionService, useValue: { exchangeEnabled: () => true } },
            { provide: ExchangeService, useValue: { grants: () => of([]), offers: () => of([]) } },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'default' } },
            { provide: ToastrService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(CatalogComponent);
    fixture.detectChanges(); // runs ngOnInit (loads the Tables tab)
    return fixture;
}

describe('CatalogComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads the Streams tab on init (data origins are the default tab)', () => {
        const c = create({ streams: () => of([STREAM]) }).componentInstance;
        expect(c.activeTab).toBe('streams');
        expect(c.streams).toEqual([STREAM]);
    });

    it('offers the two Exchange tabs when bootstrap.features.exchange is on', () => {
        const c = create().componentInstance;
        expect(c.tabs.map((t) => t.id)).toContain('shared-with-me');
        expect(c.tabs.map((t) => t.id)).toContain('shared-by-me');
    });

    it('switching to a tab loads its data', () => {
        const c = create().componentInstance;
        c.tabIndex = 3; // kpis
        c.loadTab();
        expect(c.activeTab).toBe('kpis');
        expect(c.kpis).toEqual([]);
    });

    it('degrades gracefully when the streams call fails', () => {
        const c = create({ streams: () => throwError(() => ({ status: 404 })) }).componentInstance;
        expect(c.streams).toEqual([]);
        expect(c.loading).toBe(false);
    });

    it('runs a graph traversal and derives the G6 legend', () => {
        const c = create().componentInstance;
        c.runGraph();
        expect(c.graph?.nodes).toEqual([TABLE]);
        expect(c.legend).toEqual([{ kind: 'TABLE', fill: expect.any(String) }]);
    });

    it('deep-links to the Lineage tab and runs the traversal from ?tab=graph&from=', () => {
        const c = create({}, { tab: 'graph', from: 'stream:orders' }).componentInstance;
        expect(c.activeTab).toBe('graph');
        expect(c.graphFrom).toBe('stream:orders');
        expect(c.graph?.nodes).toEqual([TABLE]); // runGraph ran on init from the deep-link
    });

    it('opens the requested tab without a from and does not traverse', () => {
        const c = create({}, { tab: 'graph' }).componentInstance;
        expect(c.activeTab).toBe('graph');
        expect(c.graph).toBeNull(); // no ?from ⇒ empty Lineage tab, user traverses manually
    });

    it('renders the empty-graph state with no a11y violations', async () => {
        const fixture = create({ graph: () => of(EMPTY_GRAPH) });
        const c = fixture.componentInstance;
        c.tabIndex = 4; // graph
        c.loadTab();
        c.runGraph();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
