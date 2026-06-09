import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CatalogComponent } from './catalog.component';
import { MetadataGraph } from '../../shared/api';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl;

/**
 * Component-logic specs for {@link CatalogComponent}. The component is constructed in an injection
 * context and its methods are driven directly with a mocked HTTP backend — the DevExtreme template
 * is never rendered (it is flaky under jsdom and not the unit under test here). We assert the data
 * flow: tab loads, graph traversal → diagram derivation, and click → detail-drawer state.
 */
describe('CatalogComponent (logic)', () => {
  let comp: CatalogComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    comp = TestBed.runInInjectionContext(() => new CatalogComponent());
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loadTab(tables) fills nodes and clears loading', () => {
    comp.loadTab(); // default tab is 'tables'
    expect(comp.loading).toBe(true);
    httpMock.expectOne(`${base}/catalog`).flush([{ id: 't1', kind: 'TABLE', label: 'Orders' }]);
    expect(comp.nodes).toHaveLength(1);
    expect(comp.loading).toBe(false);
  });

  it('loadTab(tables) on error leaves nodes empty and clears loading', () => {
    comp.loadTab();
    httpMock.expectOne(`${base}/catalog`).error(new ProgressEvent('fail'));
    expect(comp.nodes).toEqual([]);
    expect(comp.loading).toBe(false);
  });

  it('loadTab(kpis) reads the kpis array off the catalog payload', () => {
    comp.selectedIndex = 1; // 'kpis'
    comp.loadTab();
    httpMock.expectOne(`${base}/catalog/kpis`).flush({ kpis: [{ name: 'arpu' }, { name: 'churn' }] });
    expect(comp.kpis.map((k) => k.name)).toEqual(['arpu', 'churn']);
  });

  it('runGraph sends trimmed/split query params and derives the diagram from the result', () => {
    comp.graphFrom = '  arpu ';
    comp.graphDepth = 2;
    comp.graphKinds = 'KPI, REPORT';
    comp.runGraph();
    const req = httpMock.expectOne((r) => r.url === `${base}/catalog/graph`);
    expect(req.request.params.get('from')).toBe('arpu');
    expect(req.request.params.get('depth')).toBe('2');
    expect(req.request.params.get('kinds')).toBe('KPI,REPORT'); // arrays are comma-joined by toParams

    const graph: MetadataGraph = {
      nodes: [
        { id: 'arpu', kind: 'KPI', label: 'ARPU' },
        { id: 'rep', kind: 'REPORT', label: 'Daily' },
      ],
      edges: [{ from: 'arpu', to: 'rep', kind: 'USES' }],
    };
    req.flush(graph);
    expect(comp.graph).toEqual(graph);
    expect(comp.diagramNodes).toHaveLength(2);
    expect(comp.diagramEdges).toHaveLength(1);
    expect(comp.legend.map((l) => l.kind)).toEqual(['KPI', 'REPORT']);
  });

  it('runGraph on error clears the graph and its derived diagram', () => {
    comp.runGraph();
    httpMock.expectOne((r) => r.url === `${base}/catalog/graph`).error(new ProgressEvent('fail'));
    expect(comp.graph).toBeNull();
    expect(comp.diagramNodes).toEqual([]);
    expect(comp.legend).toEqual([]);
  });

  it('onDiagramItemClick opens the detail drawer for a shape and fetches the node', () => {
    comp.onDiagramItemClick({ item: { itemType: 'shape', dataItem: { id: 't1' } } });
    expect(comp.detailVisible).toBe(true);
    expect(comp.detailLoading).toBe(true);
    httpMock.expectOne(`${base}/catalog/tables/t1`).flush({ node: { id: 't1', kind: 'TABLE', label: 'X' } });
    expect(comp.detail).not.toBeNull();
    expect(comp.detailLoading).toBe(false);
  });

  it('onDiagramItemClick ignores connector clicks (no fetch, drawer stays closed)', () => {
    comp.onDiagramItemClick({ item: { itemType: 'connector', dataItem: { id: 'e1' } } });
    expect(comp.detailVisible).toBe(false);
    // afterEach httpMock.verify() asserts no request was made.
  });
});
