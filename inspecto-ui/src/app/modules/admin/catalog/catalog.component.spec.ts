import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { CatalogService, ExchangeService, MetadataGraph, MetadataNode, PipelinesService, SessionService, SpacesService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentsDataProvider } from './components-data-provider';
import { CatalogComponent } from './catalog.component';

const TABLE: MetadataNode = { id: 'tbl/cdr', kind: 'TABLE', label: 'cdr' };
const GRAPH: MetadataGraph = { nodes: [TABLE], edges: [] };
const EMPTY_GRAPH: MetadataGraph = { nodes: [], edges: [] };

function create(overrides: Partial<CatalogService> = {}) {
    const api = {
        tables: () => of([TABLE]),
        streams: () => of([]),
        kpis: () => of({ kpis: [] }),
        graph: () => of(GRAPH),
        ...overrides,
    } as unknown as CatalogService;
    TestBed.configureTestingModule({
        imports: [CatalogComponent],
        providers: [
            provideNoopAnimations(),
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
    it('loads the Tables tab on init', () => {
        const c = create().componentInstance;
        expect(c.activeTab).toBe('tables');
        expect(c.nodes).toEqual([TABLE]);
    });

    it('offers the two Exchange tabs when bootstrap.features.exchange is on', () => {
        const c = create().componentInstance;
        expect(c.tabs.map((t) => t.id)).toContain('shared-with-me');
        expect(c.tabs.map((t) => t.id)).toContain('shared-by-me');
    });

    it('switching to a tab loads its data', () => {
        const c = create().componentInstance;
        c.tabIndex = 2; // kpis
        c.loadTab();
        expect(c.activeTab).toBe('kpis');
        expect(c.kpis).toEqual([]);
    });

    it('degrades gracefully when the tables call fails', () => {
        const c = create({ tables: () => throwError(() => ({ status: 404 })) }).componentInstance;
        expect(c.nodes).toEqual([]);
        expect(c.loading).toBe(false);
    });

    it('runs a graph traversal and derives the G6 legend', () => {
        const c = create().componentInstance;
        c.runGraph();
        expect(c.graph?.nodes).toEqual([TABLE]);
        expect(c.legend).toEqual([{ kind: 'TABLE', fill: expect.any(String) }]);
    });

    it('renders the empty-graph state with no a11y violations', async () => {
        const fixture = create({ graph: () => of(EMPTY_GRAPH) });
        const c = fixture.componentInstance;
        c.tabIndex = 3; // graph
        c.loadTab();
        c.runGraph();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
