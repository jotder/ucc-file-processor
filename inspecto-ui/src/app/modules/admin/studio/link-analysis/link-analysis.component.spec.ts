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
