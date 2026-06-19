import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { FlowCombined, FlowNodeType, FlowSummary, FlowsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { FlowsComponent } from './flows.component';

const FLOW: FlowSummary = { name: 'cdr_etl', active: true, nodeCount: 5, edgeCount: 4, produces: ['cdr'], consumes: [] };
const COMBINED: FlowCombined = {
    flows: [{ name: 'cdr_etl', active: true }],
    nodes: [{ id: 'cdr_etl/acq', type: 'acquisition', category: 'SOURCE', label: 'Acquisition', flow: 'cdr_etl' }],
    edges: [],
    links: [],
};
const TYPES: FlowNodeType[] = [
    { type: 'acquisition', category: 'SOURCE', label: 'Acquisition', description: 'collect', accepts: [], emits: [], emitsNamedRoutes: false },
    { type: 'sink.view', category: 'SINK', label: 'Sink (view)', description: 'logical', accepts: [], emits: [], emitsNamedRoutes: false },
];

/** Build the component over a stub service. graph() throws so the G6 canvas is never rendered in jsdom. */
function create(listResult: Observable<FlowSummary[]>) {
    const stub = {
        list: () => listResult,
        nodeTypes: () => of(TYPES),
        graph: () => throwError(() => new Error('no graph in test')),
        combined: () => of(COMBINED),
        provenanceBatches: () => of([]),
        provenance: () => of([]),
    } as unknown as FlowsService;
    TestBed.configureTestingModule({
        imports: [FlowsComponent],
        providers: [provideNoopAnimations(), { provide: FlowsService, useValue: stub }],
    });
    const fixture = TestBed.createComponent(FlowsComponent);
    fixture.detectChanges();   // runs ngOnInit
    return fixture;
}

describe('FlowsComponent', () => {
    it('loads flows, groups node types, and auto-selects the first flow', () => {
        const c = create(of([FLOW])).componentInstance;
        expect(c.flows().length).toBe(1);
        expect(c.nodeTypeGroups().map((g) => g.category)).toEqual(['SOURCE', 'SINK']);
        expect(c.selected()).toBe('cdr_etl');
    });

    it('onNodeClick resolves the clicked node from the loaded graph', () => {
        const c = create(of([FLOW])).componentInstance;
        c.graph.set({
            name: 'cdr_etl', active: true, produces: [], consumes: [],
            nodes: [{ id: 'acq', type: 'acquisition', category: 'SOURCE', label: 'Acquisition' }],
            edges: [],
        });
        c.onNodeClick('acq');
        expect(c.selectedNode()?.id).toBe('acq');
    });

    it('renders an accessible empty state when there are no flows', async () => {
        const fixture = create(of([]));
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('selectRun overlays a run\'s per-edge counts and clearing it removes them', () => {
        const rows = [{ nodeId: 'acq', rel: 'data', rowCount: 42 }];
        const stub = {
            list: () => of([FLOW]), nodeTypes: () => of(TYPES),
            graph: () => throwError(() => new Error('no graph')), combined: () => of(COMBINED),
            provenanceBatches: () => of([{ batchId: 'b1', runTs: '2026-06-19T00:00:00Z', totalRows: 42 }]),
            provenance: () => of(rows),
        } as unknown as FlowsService;
        TestBed.configureTestingModule({
            imports: [FlowsComponent],
            providers: [provideNoopAnimations(), { provide: FlowsService, useValue: stub }],
        });
        const c = TestBed.createComponent(FlowsComponent).componentInstance;
        c.select('cdr_etl');
        expect(c.provBatches().length).toBe(1);
        c.selectRun('b1');
        expect(c.provCounts()?.get('acq|data')).toBe(42);
        c.selectRun(null);
        expect(c.provCounts()).toBeNull();
    });

    it('lazy-loads the combined topology when switching to combined mode', () => {
        const c = create(of([FLOW])).componentInstance;
        expect(c.combined()).toBeNull();
        c.setMode('combined');
        expect(c.mode()).toBe('combined');
        expect(c.combined()?.flows.length).toBe(1);
        // a store node resolves through the same inspector path
        c.onNodeClick('cdr_etl/acq');
        expect(c.selectedNode()?.id).toBe('cdr_etl/acq');
    });
});
