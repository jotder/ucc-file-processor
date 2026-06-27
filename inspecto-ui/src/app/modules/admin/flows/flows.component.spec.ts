import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { FlowCombined, FlowNodeType, FlowsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { FlowsComponent } from './flows.component';

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

/**
 * Build the component over a stub service. G6 can't instantiate in jsdom, so state tests call `load()`
 * directly (no `detectChanges` ⇒ the `<inspecto-graph-view>` canvas never mounts); the a11y test renders
 * the unavailable path (empty state, no canvas).
 */
function build(combined$: Observable<FlowCombined> = of(COMBINED)) {
    const stub = {
        nodeTypes: () => of(TYPES),
        combined: () => combined$,
    } as unknown as FlowsService;
    TestBed.configureTestingModule({
        imports: [FlowsComponent],
        providers: [provideNoopAnimations(), { provide: FlowsService, useValue: stub }],
    });
    return TestBed.createComponent(FlowsComponent);
}

describe('FlowsComponent', () => {
    it('defaults to the View tab and loads the palette + combined topology (all pipelines shown)', () => {
        const c = build().componentInstance;
        c.load();
        expect(c.mode()).toBe('combined');
        expect(c.nodeTypeGroups().map((g) => g.category)).toEqual(['SOURCE', 'SINK']);
        expect(c.combined()?.flows.length).toBe(1);
        expect(c.combinedSelected()).toEqual(['cdr_etl']);
    });

    it('onNodeClick resolves the clicked node from the combined topology', () => {
        const c = build().componentInstance;
        c.load();
        c.onNodeClick('cdr_etl/acq');
        expect(c.selectedNode()?.id).toBe('cdr_etl/acq');
    });

    it('an empty multiselect selection shows every pipeline', () => {
        const c = build().componentInstance;
        c.load();
        c.setCombinedSelected([]);
        expect(c.combinedG6()?.nodes.length).toBe(1);
    });

    it('switches to the Edit tab', () => {
        const c = build().componentInstance;
        c.load();
        c.setMode('editor');
        expect(c.mode()).toBe('editor');
    });

    it('renders an accessible empty state when the topology is unavailable', async () => {
        const fixture = build(throwError(() => new Error('down')));
        fixture.detectChanges(); // ngOnInit → load → combined errors → empty state (no G6 canvas)
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
