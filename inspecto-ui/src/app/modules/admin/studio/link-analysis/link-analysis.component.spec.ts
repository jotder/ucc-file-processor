import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { PipelinesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ElementDetailData } from 'app/inspecto/investigation';
import { G6GraphData, GraphSource } from 'app/inspecto/graph';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisComponent } from './link-analysis.component';
import { LinkAnalysisService, LinkAnalysisView } from './link-analysis.service';

const DS: Dataset = { id: 'links-ds', name: 'Links', kind: 'physical', sourceName: 'links', columns: [], measures: [], calculated: [] };

const TEST_COLOR = '#ff0000'; // a literal test value passed to setNodeColor(), not app styling — ds-allow

/** A tiny two-cluster graph: a–b–c plus d–e. */
const GRAPH: G6GraphData = {
    nodes: ['a', 'b', 'c', 'd', 'e'].map((id) => ({ id, data: { label: id.toUpperCase(), kind: id < 'd' ? 'entity' : 'other' } })),
    edges: [
        { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link' } },
        { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link' } },
        { id: 'd->e', source: 'd', target: 'e', data: { kind: 'link' } },
    ],
};

function create(opts: { fail?: boolean; views?: LinkAnalysisView[]; expand?: GraphSource['expand']; graph?: G6GraphData; queryParams?: Record<string, string> } = {}) {
    const queried: unknown[] = [];
    const fakeSource: GraphSource = {
        id: 'entity-projection',
        label: 'Entity/Link (from a Dataset)',
        query: (q) => {
            queried.push(q);
            return opts.fail ? Promise.reject(new Error('bad mapping')) : Promise.resolve(opts.graph ?? GRAPH);
        },
        expand: opts.expand,
    };
    const save = vi.fn((v: LinkAnalysisView) => of(v));
    TestBed.configureTestingModule({
        imports: [LinkAnalysisComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GraphSourcesService, useValue: { sources: [fakeSource], byId: () => fakeSource } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: PipelinesService, useValue: { list: () => of([]) } },
            { provide: LinkAnalysisService, useValue: { list: () => of(opts.views ?? []), save } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined, info: () => undefined } },
            {
                provide: ActivatedRoute,
                useValue: { snapshot: { queryParamMap: convertToParamMap(opts.queryParams ?? {}) } },
            },
        ],
    });
    return { fixture: TestBed.createComponent(LinkAnalysisComponent), queried, save };
}

// After a successful load the state is driven directly (no detectChanges) —
// the G6 host can't instantiate in jsdom (see registry.component.spec.ts).
async function runQuery(fixture: ReturnType<typeof create>['fixture']): Promise<void> {
    const c = fixture.componentInstance;
    c.queryForm.patchValue({ datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' });
    await c.run();
}

describe('LinkAnalysisComponent', () => {
    it('renders the empty state, is a11y-clean, and validates the mapping before querying', async () => {
        const { fixture, queried } = create();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No graph yet');
        await fixture.componentInstance.run(); // no mapping picked yet
        expect(fixture.componentInstance.loadError()).toMatch(/source and target/);
        expect(queried).toHaveLength(0);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('runs the query, shows the graph counts, and search/kind filters drive emphasis + display', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.graph()).toEqual(GRAPH);
        expect(c.nodeKinds()).toEqual(['entity', 'other']);

        c.onSearch('a');
        expect(c.emphasis()?.nodeIds).toEqual(['a']);

        c.toggleKind('other', false);
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'b', 'c']);
        c.clearFilters();
        expect(c.displayed()?.nodes).toHaveLength(5);
    });

    it('offers a pivot to the map for a node carrying an objectRef (ui-design-review R8)', async () => {
        const graphWithRef: G6GraphData = {
            nodes: [...GRAPH.nodes, { id: 'f', data: { label: 'F', kind: 'entity', objectRef: { id: 'case-1', type: 'CASE' } } }],
            edges: GRAPH.edges,
        };
        const { fixture } = create({ graph: graphWithRef });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
        const dataOf = (call: number): ElementDetailData => (openSpy.mock.calls[call][1] as { data: ElementDetailData }).data;

        c.onNodeClick('a'); // plain node — no objectRef, no pivot offered
        expect(dataOf(0).pivotViews).toBeUndefined();

        c.onNodeClick('f');
        expect(dataOf(1).objectRef).toEqual({ id: 'case-1', type: 'CASE' });
        expect(dataOf(1).pivotViews).toEqual(['map']);
    });

    it('resolves an incoming investigation pivot against the loaded graph, or toasts if absent', async () => {
        const graphWithRef: G6GraphData = {
            nodes: [...GRAPH.nodes, { id: 'f', data: { label: 'F', kind: 'entity', objectRef: { id: 'case-1', type: 'CASE' } } }],
            edges: GRAPH.edges,
        };
        const { fixture } = create({ graph: graphWithRef, queryParams: { pivotId: 'case-1', pivotType: 'CASE' } });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        await runQuery(fixture);
        expect(c.emphasis()?.nodeIds).toEqual(['f']);
    });

    it('toasts when the pivoted-in record is not in the loaded graph', async () => {
        const { fixture } = create({ queryParams: { pivotId: 'case-missing', pivotType: 'CASE' } });
        fixture.detectChanges();
        const toastr = TestBed.inject(ToastrService);
        const infoSpy = vi.spyOn(toastr, 'info');
        await runQuery(fixture);
        expect(infoSpy).toHaveBeenCalled();
    });

    // Analysis-toolbox logic (shortest path, explain, centrality, communities, all-paths, components,
    // patterns, tool badges) lives in LinkAnalysisToolboxComponent — see its own spec.

    it('expandNode (Phase E): merges the source\'s one-hop neighborhood into the loaded graph, filters intact', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
        }));
        const { fixture } = create({ expand });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.onSearch('a'); // some filter/analysis state that must survive the merge

        await c.expandNode('c', 'C');
        expect(expand).toHaveBeenCalledWith('c', 'C', expect.objectContaining({ projection: expect.anything() }));
        expect(c.graph()?.nodes.map((n) => n.id).sort()).toEqual(['a', 'b', 'c', 'd', 'e', 'f']);
        expect(c.graph()?.edges.map((e) => e.id)).toContain('c->f');
        expect(c.emphasis()?.nodeIds).toEqual(['a']); // untouched by the merge
    });

    it('expandNode is a no-op when the source has no expand()', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        await c.expandNode('c', 'C');
        expect(c.graph()).toEqual(GRAPH); // unchanged
    });

    it('undo/redo (Phase G): a sequence of display mutations restores exact prior signal values', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.canUndo()).toBe(false);

        c.setNodeColor('entity', TEST_COLOR);
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR });
        expect(c.canUndo()).toBe(true);

        c.toggleKind('other', false); // a second, independent mutation
        expect(c.kindFilter()).toEqual(['entity']);

        c.undoPresentation();
        expect(c.kindFilter()).toEqual([]); // back to before the toggle
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR }); // the color change is untouched
        expect(c.canRedo()).toBe(true);

        c.undoPresentation();
        expect(c.nodeColors()).toEqual({}); // back to before the color change
        expect(c.canUndo()).toBe(false);

        c.redoPresentation();
        c.redoPresentation();
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR });
        expect(c.kindFilter()).toEqual(['entity']);
        expect(c.canRedo()).toBe(false);
    });

    it('undo/redo: a fresh run() clears history (stale snapshots would reference a different graph)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.setNodeColor('entity', TEST_COLOR);
        expect(c.canUndo()).toBe(true);
        await runQuery(fixture);
        expect(c.canUndo()).toBe(false);
    });

    it('exportGraphml (Phase F): downloads the displayed graph as generic GraphML', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
        c.exportGraphml();
        expect(clickSpy).toHaveBeenCalled();
        clickSpy.mockRestore();
    });

    it('smart form: auto-collapses to the selected-values summary after a run, and edit reopens it', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.queryOpen()).toBe(true); // full form until something runs
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.queryOpen()).toBe(false); // collapsed — the summary + status bar take over
        expect(c.sourceLabel()).toBe('Entity/Link (from a Dataset)');
        expect(c.querySummary().map((i) => `${i.label}: ${i.value}`)).toEqual(['Dataset: Links', 'Mapping: source → target']);

        c.bottomTab.set('data');
        c.editQuery(); // the status-bar pencil jumps back to the Query tab, form expanded
        expect(c.bottomTab()).toBe('query');
        expect(c.bottomOpen()).toBe(true);
        expect(c.queryOpen()).toBe(true);
    });

    it('workspace: a failed query keeps the form open; openAnalysis opens the bottom Analysis tab', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.queryOpen()).toBe(true); // a failing query needs its form back
        expect(c.querySummary()).toEqual([]);

        c.bottomOpen.set(false);
        c.openAnalysis(); // the toolbar algorithms icon opens the bottom Analysis tab
        expect(c.bottomOpen()).toBe(true);
        expect(c.bottomTab()).toBe('analysis');
    });

    it('surfaces a failing source as an inline error, not a blank pane', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges(); // graph stays null on failure, so the G6 host never mounts
        expect(fixture.componentInstance.loadError()).toBe('bad mapping');
        expect(fixture.nativeElement.textContent).toContain('Query failed');
    });

    it('collapse/expand branches hide and restore a node’s downstream subtree', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.collapseBranch('b'); // a→b→c: hides c, keeps b
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'b', 'd', 'e']);
        c.expandBranch('b');
        expect(c.displayed()?.nodes).toHaveLength(5);

        c.collapseBranch('a');
        c.collapseBranch('d');
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'd']);
        c.expandAll();
        expect(c.displayed()?.nodes).toHaveLength(5);
    });

    it('display options travel with a saved view and are re-applied on load', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.displayCustomized()).toBe(false);
        c.edgeLabels.set(false);
        c.setNodeColor('entity', c.swatches[0]);
        c.setNodeColor('entity', c.swatches[1]); // re-pick replaces
        c.setEdgeColor('link', c.swatches[2]);
        c.setNodeShape('entity', 'diamond');
        c.setEdgePattern('link', 'dashed');
        c.setEdgeSize('link', 3);
        expect(c.displayCustomized()).toBe(true);

        c.saveForm.patchValue({ name: 'Styled' });
        await c.saveView();
        const saved = save.mock.calls[0][0];
        expect(saved.display).toEqual({
            nodeLabels: true, edgeLabels: false,
            nodeColors: { entity: c.swatches[1] }, edgeColors: { link: c.swatches[2] },
            nodeShapes: { entity: 'diamond' }, edgePatterns: { link: 'dashed' }, edgeSizes: { link: 3 },
        });

        c.setNodeColor('entity', null); // drift away, then load restores the captured styling
        c.setNodeShape('entity', null);
        c.setEdgePattern('link', null);
        c.edgeLabels.set(true);
        await c.loadView(saved);
        expect(c.edgeLabels()).toBe(false);
        expect(c.nodeColors()).toEqual({ entity: c.swatches[1] });
        expect(c.nodeShapes()).toEqual({ entity: 'diamond' });
        expect(c.edgePatterns()).toEqual({ link: 'dashed' });
        expect(c.edgeSizes()).toEqual({ link: 3 });

        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.displayCustomized()).toBe(false); // a view without display resets to defaults
    });

    it('layout: defaults to dagre, gates tree layouts on the graph shape, and travels with a saved view', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.layoutId()).toBe('dagre');
        expect(c.isTreeShaped()).toBe(true); // GRAPH (a→b→c, d→e) is a forest — tree layouts enabled

        c.setLayout('radial');
        expect(c.layoutId()).toBe('radial');

        c.saveForm.patchValue({ name: 'Radial view' });
        await c.saveView();
        expect(save.mock.calls[0][0].layout).toBe('radial');

        c.setLayout('mindmap'); // drift, then load restores the captured layout
        await c.loadView({ id: 'r', name: 'Radial view', sourceId: 'entity-projection', query: {}, layout: 'radial' });
        expect(c.layoutId()).toBe('radial');

        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.layoutId()).toBe('dagre'); // a view without a layout resets to the default
    });

    it('the bottom panel tables the displayed graph and narrows with the search', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.tableRows()).toHaveLength(3); // links mode: all three edges
        c.tableMode.set('nodes');
        expect(c.tableRows()).toHaveLength(5);
        expect(c.tableRows()[0]).toEqual({ label: 'A', kind: 'entity', links: 1, id: 'a' });

        c.onSearch('a'); // search narrows the table to the matched node…
        expect(c.tableRows()).toEqual([{ label: 'A', kind: 'entity', links: 1, id: 'a' }]);
        c.tableMode.set('links');
        expect(c.tableRows().map((r) => r['id'])).toEqual(['a->b']); // …and to links touching it

        c.toggleKind('other', false); // the kind filter flows through too
        c.onSearch('');
        expect(c.tableRows().map((r) => r['id'])).toEqual(['a->b', 'b->c']);
    });

    it('saves a view (duplicate name blocked inline) and reloads a saved view', async () => {
        const existing: LinkAnalysisView = { id: 'ring', name: 'Ring', sourceId: 'entity-projection', query: {} };
        const { fixture, save, queried } = create({ views: [existing] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.saveForm.patchValue({ name: 'Ring' });
        await c.saveView();
        expect(c.saveForm.controls.name.hasError('duplicate')).toBe(true);
        expect(save).not.toHaveBeenCalled();

        c.saveForm.patchValue({ name: 'Device ring' });
        await c.saveView();
        expect(save).toHaveBeenCalledOnce();
        expect(c.views().map((v) => v.name)).toContain('Device ring');

        await c.loadView(existing);
        expect(queried.length).toBeGreaterThan(1);
        expect(c.sourceId()).toBe('entity-projection');
    });
});
