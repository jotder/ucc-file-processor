import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { PipelinesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { G6GraphData, GraphSource } from 'app/inspecto/graph';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisComponent } from './link-analysis.component';
import { LinkAnalysisService, LinkAnalysisView } from './link-analysis.service';

const DS: Dataset = { id: 'links-ds', name: 'Links', kind: 'physical', sourceName: 'links', columns: [], measures: [] };

/** A tiny two-cluster graph: a–b–c plus d–e. */
const GRAPH: G6GraphData = {
    nodes: ['a', 'b', 'c', 'd', 'e'].map((id) => ({ id, data: { label: id.toUpperCase(), kind: id < 'd' ? 'entity' : 'other' } })),
    edges: [
        { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link' } },
        { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link' } },
        { id: 'd->e', source: 'd', target: 'e', data: { kind: 'link' } },
    ],
};

function create(opts: { fail?: boolean; views?: LinkAnalysisView[] } = {}) {
    const queried: unknown[] = [];
    const fakeSource: GraphSource = {
        id: 'entity-projection',
        label: 'Entity/Link (from a Dataset)',
        query: (q) => {
            queried.push(q);
            return opts.fail ? Promise.reject(new Error('bad mapping')) : Promise.resolve(GRAPH);
        },
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
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
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

    it('analysis: path, explain, centrality and communities all work over the loaded graph', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runPath();
        expect(c.pathResult()?.hops).toEqual(['a', 'b', 'c']);
        expect(c.emphasis()?.edgeIds).toEqual(['a->b', 'b->c']);

        c.pathTo.set('e');
        c.runPath();
        expect(c.analysisError()).toMatch(/No path/);

        c.explainFor.set('b');
        c.runExplain();
        expect(c.explainText()).toContain('B (entity)');

        c.runCentrality();
        expect(c.ranking()[0].id).toBe('b'); // the a–b–c middle has the highest degree

        c.runCommunities();
        expect(c.communities()).toHaveLength(2);
        expect(c.emphasis()?.groups?.get('a')).toBe(c.emphasis()?.groups?.get('c'));
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

        c.leftOpen.set(false);
        c.editQuery(); // the status-bar pencil / collapsed-strip tool
        expect(c.leftOpen()).toBe(true);
        expect(c.queryOpen()).toBe(true);
    });

    it('workspace: a failed query keeps the form open; openTool/toggleTool drive the analysis toolbox', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.queryOpen()).toBe(true); // a failing query needs its form back
        expect(c.querySummary()).toEqual([]);

        c.rightOpen.set(false);
        c.openTool('communities'); // collapsed-strip icon expands straight onto the tool
        expect(c.rightOpen()).toBe(true);
        expect(c.tab()).toBe('communities');
        c.toggleTool('communities'); // clicking the open header collapses the group
        expect(c.tab()).toBeNull();
        c.toggleTool('path');
        expect(c.tab()).toBe('path');
    });

    it('tool-group headers chip their results after each analysis runs', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.toolBadge('path')).toBe('');

        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runPath();
        expect(c.toolBadge('path')).toBe('3 hops');

        c.explainFor.set('b');
        c.runExplain();
        expect(c.toolBadge('explain')).toBe('B');

        c.runCentrality();
        expect(c.toolBadge('centrality')).toBe('top 5');

        c.runCommunities();
        expect(c.toolBadge('communities')).toBe('2 found');
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
        expect(c.displayCustomized()).toBe(true);

        c.saveForm.patchValue({ name: 'Styled' });
        await c.saveView();
        const saved = save.mock.calls[0][0];
        expect(saved.display).toEqual({
            nodeLabels: true, edgeLabels: false,
            nodeColors: { entity: c.swatches[1] }, edgeColors: { link: c.swatches[2] },
        });

        c.setNodeColor('entity', null); // drift away, then load restores the captured styling
        c.edgeLabels.set(true);
        await c.loadView(saved);
        expect(c.edgeLabels()).toBe(false);
        expect(c.nodeColors()).toEqual({ entity: c.swatches[1] });

        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.displayCustomized()).toBe(false); // a view without display resets to defaults
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
