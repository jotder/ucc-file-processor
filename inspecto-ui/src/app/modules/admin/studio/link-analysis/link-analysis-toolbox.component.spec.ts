import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { of } from 'rxjs';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { G6GraphData } from 'app/inspecto/graph';
import { GraphEmphasis } from 'app/modules/admin/catalog/graph-view.component';
import { LinkAnalysisToolboxComponent } from './link-analysis-toolbox.component';

/** A tiny two-cluster graph: a–b–c plus d–e (mirrors the studio spec fixture). */
const GRAPH: G6GraphData = {
    nodes: ['a', 'b', 'c', 'd', 'e'].map((id) => ({ id, data: { label: id.toUpperCase(), kind: id < 'd' ? 'entity' : 'other' } })),
    edges: [
        { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link' } },
        { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link' } },
        { id: 'd->e', source: 'd', target: 'e', data: { kind: 'link' } },
    ],
};

function make(graph: G6GraphData | null = GRAPH) {
    TestBed.configureTestingModule({
        imports: [LinkAnalysisToolboxComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisToolboxComponent);
    const c = fixture.componentInstance;
    fixture.componentRef.setInput('graph', graph);
    fixture.componentRef.setInput('nodeOptions', (graph?.nodes ?? []).map((n) => ({ id: n.id, label: n.data.label })));
    fixture.componentRef.setInput('nodeKinds', ['entity', 'other']);
    fixture.componentRef.setInput('edgeKinds', ['link']);
    fixture.componentRef.setInput('labelOf', (id: string) => graph?.nodes.find((n) => n.id === id)?.data.label ?? id);
    const emphases: (GraphEmphasis | null)[] = [];
    c.emphasisChange.subscribe((e) => emphases.push(e));
    return { fixture, c, emphases, last: () => emphases[emphases.length - 1] };
}

describe('LinkAnalysisToolboxComponent', () => {
    it('shortest path: finds the hops and emits the path emphasis, else an inline error', () => {
        const { c, last } = make();
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runPath();
        expect(c.pathResult()?.hops).toEqual(['a', 'b', 'c']);
        expect(last()?.edgeIds).toEqual(['a->b', 'b->c']);

        c.pathTo.set('e');
        c.runPath();
        expect(c.analysisError()).toMatch(/No path/);
    });

    it('explain, centrality and communities work over the graph', () => {
        const { c, last } = make();
        c.explainFor.set('b');
        c.runExplain();
        expect(c.explainText()).toContain('B (entity)');

        c.runCentrality();
        expect(c.ranking()[0].id).toBe('b'); // the a–b–c middle has the highest degree

        c.runCommunities();
        expect(c.communities()).toHaveLength(2);
        expect(last()?.groups?.get('a')).toBe(last()?.groups?.get('c'));
    });

    it('all paths + connected components partition the graph', () => {
        const { c } = make();
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runAllPaths();
        expect(c.allPathsResult()).toHaveLength(1);
        expect(c.allPathsResult()[0].nodeIds).toEqual(['a', 'b', 'c']);

        c.pathTo.set('e');
        c.runAllPaths();
        expect(c.analysisError()).toMatch(/No path/);

        c.runConnectedComponents();
        expect(c.components()).toHaveLength(2);
        expect(c.components()[0]).toHaveLength(3); // a-b-c is the larger component, sorted first
    });

    it('communities: the Louvain method also groups the graph and paints group emphasis', () => {
        const { c, last } = make();
        c.communityMethod.set('louvain');
        c.runCommunities();
        expect(c.communities()).toHaveLength(2); // a–b–c and d–e
        expect(c.toolBadge('communities')).toBe('2 found');
        expect(last()?.groups?.get('a')).toBe(last()?.groups?.get('c'));
        expect(last()?.groups?.get('a')).not.toBe(last()?.groups?.get('d'));
    });

    it('accordion + result chips: toggleTool opens/collapses, toolBadge chips each result', () => {
        const { c } = make();
        c.toggleTool('communities');
        expect(c.tab()).toBe('communities');
        c.toggleTool('communities');
        expect(c.tab()).toBeNull();
        c.toggleTool('path');
        expect(c.tab()).toBe('path');

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

    it('pattern: builds a motif, matches it, gates on node kind, and focuses a match', () => {
        const { c, last } = make();

        // default motif = any start → any out-edge → any node: every out-edge (a→b, b→c, d→e)
        c.runPattern();
        expect(c.patternMatches()).toHaveLength(3);
        expect(c.toolBadge('pattern')).toBe('3 matches');
        expect(last()?.nodeIds).toEqual(expect.arrayContaining(['a', 'b', 'c', 'd', 'e']));

        // constrain the start to 'entity' (a,b,c): only a→b and b→c remain
        c.updatePatternStep(0, { nodeKind: 'entity' });
        c.runPattern();
        expect(c.patternMatches()).toHaveLength(2);

        c.addPatternStep();
        expect(c.patternSteps()).toHaveLength(3);
        c.removePatternStep(2);
        expect(c.patternSteps()).toHaveLength(2);
        c.removePatternStep(0); // never drops below one step
        expect(c.patternSteps()).toHaveLength(1);

        const first = c.patternMatches()[0];
        c.focusMatch(first);
        expect(last()?.nodeIds).toEqual(first.nodeIds);

        // a motif with no occurrence surfaces an inline message, not a blank result
        c.patternSteps.set([{ nodeKind: 'other' }, { edgeKind: 'nope', direction: 'out' }]);
        c.runPattern();
        expect(c.patternMatches()).toHaveLength(0);
        expect(c.analysisError()).toMatch(/No matches/);
    });

    it('reset() clears results but keeps presentation choices (open tab, metric, motif)', () => {
        const { c } = make();
        c.tab.set('centrality');
        c.centralityMetric.set('betweenness');
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runPath();
        expect(c.pathResult()).not.toBeNull();

        c.reset();
        expect(c.pathResult()).toBeNull();
        expect(c.analysisError()).toBe('');
        expect(c.pathFrom()).toBe('');
        // presentation-only choices persist
        expect(c.tab()).toBe('centrality');
        expect(c.centralityMetric()).toBe('betweenness');
    });

    it('weighted path prefers strongest ties; centrality supports the extended metric set', () => {
        const wg: G6GraphData = {
            nodes: ['a', 'b', 'c'].map((id) => ({ id, data: { label: id.toUpperCase(), kind: 'entity' } })),
            edges: [
                { id: 'a->c', source: 'a', target: 'c', data: { kind: 'link' } },
                { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link · 5' } },
                { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link · 5' } },
            ],
        };
        const { c } = make(wg);
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.pathMetric.set('weighted');
        c.runPath();
        expect(c.pathResult()?.hops).toEqual(['a', 'b', 'c']); // strongest ties, not fewest hops
        c.pathMetric.set('hops');
        c.runPath();
        expect(c.pathResult()?.hops).toEqual(['a', 'c']);

        for (const metric of ['closeness', 'eigenvector', 'katz', 'pagerank', 'hub', 'authority'] as const) {
            c.centralityMetric.set(metric);
            c.runCentrality();
            expect(c.ranking().length).toBeGreaterThan(0);
        }
    });

    it('V2 groups: cycles, cut points, cohesion, similarity, flow and scoring all run', () => {
        // triangle a-b-c plus two pendants x,y hanging off the hub a.
        const gr: G6GraphData = {
            nodes: ['a', 'b', 'c', 'x', 'y'].map((id) => ({ id, data: { label: id.toUpperCase(), kind: 'entity' } })),
            edges: [
                { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link' } },
                { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link' } },
                { id: 'c->a', source: 'c', target: 'a', data: { kind: 'link' } },
                { id: 'x->a', source: 'x', target: 'a', data: { kind: 'link' } },
                { id: 'y->a', source: 'y', target: 'a', data: { kind: 'link' } },
            ],
        };
        const { c } = make(gr);

        c.runCycles();
        expect(c.cycles().length).toBeGreaterThan(0);
        expect(c.toolBadge('cycles')).toMatch(/found/);

        c.runCutPoints();
        expect(c.cutNodes()).toContain('a'); // removing the hub isolates x and y

        c.cohesionMetric.set('k-core');
        c.runCohesion();
        expect(c.cohesionRanking().length).toBeGreaterThan(0);
        c.cohesionMetric.set('cliques');
        c.runCohesion();
        expect(c.cliquesResult().some((cl) => cl.length === 3)).toBe(true); // the a-b-c triangle

        c.similarityFor.set('x');
        c.runSimilarity();
        expect(c.similarityResult()[0].id).toBe('y'); // x and y share exactly neighbor a
        c.runPrediction();
        expect(c.predictions().length).toBeGreaterThan(0);

        c.flowFrom.set('x');
        c.flowTo.set('c');
        c.runFlow();
        expect(c.flowResult()?.value).toBeGreaterThan(0);
        c.runSpanningForest();
        expect(c.spanningForest()?.edgeIds.length).toBeGreaterThan(0);

        c.runScoring();
        expect(c.suspicion()[0].id).toBe('a'); // the hub scores highest
        expect(c.suspicion()[0].score).toBeGreaterThan(0);
    });

    it('pattern packs: loading a pack fills the motif and exposes its hint', () => {
        const { c } = make();
        c.loadPatternPack('layering-chain');
        expect(c.loadedPack()?.id).toBe('layering-chain');
        expect(c.patternSteps()).toHaveLength(4); // A → B → C → D
        // editing a loaded step must not mutate the shared catalog
        c.updatePatternStep(1, { nodeKind: 'account' });
        expect(c.patternPacks.find((p) => p.id === 'layering-chain')!.steps[1].nodeKind).toBeUndefined();

        c.loadPatternPack('circular-flow');
        expect(c.loadedPack()?.tool).toBe('cycles');

        c.loadPatternPack(''); // back to a custom motif
        expect(c.loadedPack()).toBeNull();
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = make();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
