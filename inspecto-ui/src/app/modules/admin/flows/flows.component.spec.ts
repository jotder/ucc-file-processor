import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { FlowNodeType, FlowSummary, FlowsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { FlowsComponent } from './flows.component';

const FLOW: FlowSummary = { name: 'cdr_etl', active: true, nodeCount: 5, edgeCount: 4, produces: ['cdr'], consumes: [] };
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
});
