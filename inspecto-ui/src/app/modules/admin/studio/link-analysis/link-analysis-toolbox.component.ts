import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import {
    G6GraphData,
    GraphSelection,
    NodeScore,
    PatternStep,
    PredictedLink,
    SuspicionScore,
    allPaths,
    articulationPoints,
    betweennessCentrality,
    bridges,
    cliques,
    closenessCentrality,
    connectedComponents,
    degreeCentrality,
    detectCommunities,
    eigenvectorCentrality,
    explainNode,
    findCycles,
    hits,
    jaccardSimilarity,
    kCore,
    katzCentrality,
    linkPrediction,
    louvainCommunities,
    matchPattern,
    maxFlow,
    maximumSpanningForest,
    neighborhood,
    pageRank,
    shortestPath,
    suspicionScore,
    triangleCount,
    weightedShortestPath,
} from 'app/inspecto/graph';
import { GraphEmphasis } from 'app/modules/admin/catalog/graph-view.component';
import { PATTERN_PACKS, PatternPack } from './pattern-packs';

type AnalysisTab =
    | 'path' | 'explain' | 'centrality' | 'communities' | 'pattern' | 'all-paths' | 'components'
    | 'cycles' | 'cut-points' | 'cohesion' | 'similarity' | 'flow' | 'scoring';

/** The metrics the Centrality group can rank by — each returns a {@link NodeScore} list. */
type CentralityMetric = 'degree' | 'betweenness' | 'closeness' | 'eigenvector' | 'katz' | 'pagerank' | 'hub' | 'authority';

/**
 * **Link Analysis — graph-algorithms toolbox** (the bottom panel's Analysis tab, extracted from the
 * studio god component per plan S2/B4). A self-contained panel that runs the pure `graph-analysis`
 * library over the currently-displayed graph and emits the resulting {@link GraphEmphasis} for the
 * host to paint on the canvas. Owns its own result state so a saved analysis survives tab switches
 * (the host mounts it with `[hidden]`, never `@if`, so this instance is never torn down).
 */
@Component({
    selector: 'inspecto-link-analysis-toolbox',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        DecimalPipe, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule,
        MatInputModule, MatSelectModule, InspectoAlertComponent,
    ],
    templateUrl: './link-analysis-toolbox.component.html',
})
export class LinkAnalysisToolboxComponent {
    /** The graph the tools operate on — the host's displayed (filtered + collapsed) graph. */
    readonly graph = input<G6GraphData | null>(null);
    readonly nodeOptions = input<{ id: string; label: string }[]>([]);
    readonly nodeKinds = input<string[]>([]);
    readonly edgeKinds = input<string[]>([]);
    /** Node-id → label lookup, supplied by the host (its full-graph labels). */
    readonly labelOf = input<(id: string) => string>((id) => id);

    /** A selection to emphasize on the canvas (`null` clears). */
    readonly emphasisChange = output<GraphEmphasis | null>();

    /** The analysis tool groups (the accordion = the graph-algorithms toolbox). */
    readonly tools: { id: AnalysisTab; label: string; icon: string }[] = [
        { id: 'path', label: 'Shortest path', icon: 'heroicons_outline:arrows-right-left' },
        { id: 'all-paths', label: 'All paths', icon: 'heroicons_outline:share' },
        { id: 'explain', label: 'Explain node', icon: 'heroicons_outline:light-bulb' },
        { id: 'centrality', label: 'Centrality', icon: 'heroicons_outline:star' },
        { id: 'communities', label: 'Communities', icon: 'heroicons_outline:user-group' },
        { id: 'components', label: 'Connected components', icon: 'heroicons_outline:squares-2x2' },
        { id: 'cycles', label: 'Cycles', icon: 'heroicons_outline:arrow-path' },
        { id: 'cut-points', label: 'Cut points', icon: 'heroicons_outline:scissors' },
        { id: 'cohesion', label: 'Cohesive groups', icon: 'heroicons_outline:cube' },
        { id: 'similarity', label: 'Similarity & prediction', icon: 'heroicons_outline:sparkles' },
        { id: 'flow', label: 'Flow & backbone', icon: 'heroicons_outline:beaker' },
        { id: 'scoring', label: 'Suspicion score', icon: 'heroicons_outline:shield-exclamation' },
        { id: 'pattern', label: 'Pattern match', icon: 'heroicons_outline:magnifying-glass-circle' },
    ];

    /** The open tool group (accordion: one open at a time; `null` = all collapsed). */
    readonly tab = signal<AnalysisTab | null>('path');
    readonly pathFrom = signal('');
    readonly pathTo = signal('');
    /** Shortest path by fewest hops, or by strongest ties (weighted). */
    readonly pathMetric = signal<'hops' | 'weighted'>('hops');
    readonly explainFor = signal('');
    readonly explainHops = signal(1);
    readonly centralityMetric = signal<CentralityMetric>('degree');
    readonly analysisError = signal('');
    readonly pathResult = signal<{ hops: string[] } | null>(null);
    readonly allPathsResult = signal<GraphSelection[]>([]);
    readonly explainText = signal('');
    readonly ranking = signal<NodeScore[]>([]);
    readonly communityMethod = signal<'label-prop' | 'louvain'>('label-prop');
    readonly communities = signal<{ id: string; members: string[] }[]>([]);
    readonly components = signal<string[][]>([]);
    /** The pattern-match motif — step 0 = the start node; each later step traverses one edge. */
    readonly patternSteps = signal<PatternStep[]>([{}, { direction: 'out' }]);
    readonly patternMatches = signal<GraphSelection[]>([]);
    /** The built-in pattern packs (parameterized starter motifs) + the one loaded, for its hint. */
    readonly patternPacks = PATTERN_PACKS;
    readonly loadedPack = signal<PatternPack | null>(null);
    // ── V2 result state (each group keeps its own so results survive tab switches) ──
    readonly cycles = signal<GraphSelection[]>([]);
    readonly cutNodes = signal<string[]>([]);
    readonly cutEdges = signal<string[]>([]);
    readonly cohesionMetric = signal<'k-core' | 'triangles' | 'cliques'>('k-core');
    readonly cohesionRanking = signal<NodeScore[]>([]);
    readonly cliquesResult = signal<string[][]>([]);
    readonly similarityFor = signal('');
    readonly similarityResult = signal<NodeScore[]>([]);
    readonly predictions = signal<PredictedLink[]>([]);
    readonly flowFrom = signal('');
    readonly flowTo = signal('');
    readonly flowResult = signal<{ value: number; minCut: GraphSelection } | null>(null);
    readonly spanningForest = signal<GraphSelection | null>(null);
    readonly suspicion = signal<SuspicionScore[]>([]);

    /** Node label via the host-supplied lookup. */
    label(id: string): string {
        return this.labelOf()(id);
    }

    /** Clear all result state (called by the host when a fresh graph is loaded). Presentation-only
     *  choices (open tab, hop count, metric, motif) deliberately persist, matching the prior behavior. */
    reset(): void {
        this.pathResult.set(null);
        this.allPathsResult.set([]);
        this.explainText.set('');
        this.ranking.set([]);
        this.communities.set([]);
        this.components.set([]);
        this.patternMatches.set([]);
        this.cycles.set([]);
        this.cutNodes.set([]);
        this.cutEdges.set([]);
        this.cohesionRanking.set([]);
        this.cliquesResult.set([]);
        this.similarityResult.set([]);
        this.predictions.set([]);
        this.flowResult.set(null);
        this.spanningForest.set(null);
        this.suspicion.set([]);
        this.analysisError.set('');
        this.pathFrom.set('');
        this.pathTo.set('');
        this.explainFor.set('');
        this.similarityFor.set('');
        this.flowFrom.set('');
        this.flowTo.set('');
    }

    /** Accordion header click — open this group, or collapse it if already open. */
    toggleTool(tool: AnalysisTab): void {
        this.tab.set(this.tab() === tool ? null : tool);
    }

    /** The result chip on a tool-group header (empty until that analysis has run). */
    toolBadge(tool: AnalysisTab): string {
        switch (tool) {
            case 'path': {
                const p = this.pathResult();
                return p ? `${p.hops.length} hops` : '';
            }
            case 'explain':
                return this.explainText() ? this.label(this.explainFor()) : '';
            case 'centrality':
                return this.ranking().length ? `top ${this.ranking().length}` : '';
            case 'communities':
                return this.communities().length ? `${this.communities().length} found` : '';
            case 'pattern':
                return this.patternMatches().length ? `${this.patternMatches().length} matches` : '';
            case 'all-paths':
                return this.allPathsResult().length ? `${this.allPathsResult().length} paths` : '';
            case 'components':
                return this.components().length ? `${this.components().length} found` : '';
            case 'cycles':
                return this.cycles().length ? `${this.cycles().length} found` : '';
            case 'cut-points':
                return this.cutNodes().length || this.cutEdges().length
                    ? `${this.cutNodes().length} nodes · ${this.cutEdges().length} bridges` : '';
            case 'cohesion':
                return this.cohesionMetric() === 'cliques'
                    ? (this.cliquesResult().length ? `${this.cliquesResult().length} cliques` : '')
                    : (this.cohesionRanking().length ? `top ${this.cohesionRanking().length}` : '');
            case 'similarity':
                return this.similarityResult().length || this.predictions().length
                    ? `${this.similarityResult().length} similar · ${this.predictions().length} predicted` : '';
            case 'flow': {
                const f = this.flowResult();
                return f ? `flow ${f.value}` : (this.spanningForest() ? `${this.spanningForest()!.edgeIds.length} edges` : '');
            }
            case 'scoring':
                return this.suspicion().length ? `top ${this.suspicion().length}` : '';
        }
    }

    // ── analysis ──

    runPath(): void {
        const g = this.graph();
        if (!g || !this.pathFrom() || !this.pathTo()) return;
        this.analysisError.set('');
        const p = this.pathMetric() === 'weighted'
            ? weightedShortestPath(g, this.pathFrom(), this.pathTo())
            : shortestPath(g, this.pathFrom(), this.pathTo());
        if (!p) {
            this.pathResult.set(null);
            this.emphasisChange.emit(null);
            this.analysisError.set('No path connects the two nodes.');
            return;
        }
        this.pathResult.set({ hops: p.nodeIds });
        this.emphasisChange.emit({ nodeIds: p.nodeIds, edgeIds: p.edgeIds });
    }

    runExplain(): void {
        const g = this.graph();
        const id = this.explainFor();
        if (!g || !id) return;
        const nb = neighborhood(g, id, this.explainHops());
        this.explainText.set(explainNode(g, id));
        this.emphasisChange.emit({ nodeIds: nb.nodes.map((n) => n.id), edgeIds: nb.edges.map((e) => e.id) });
    }

    runCentrality(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        try {
            this.ranking.set(this.centralityScores(g).slice(0, 20));
            this.emphasisChange.emit(null);
        } catch (err) {
            this.ranking.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    private centralityScores(g: G6GraphData): NodeScore[] {
        switch (this.centralityMetric()) {
            case 'betweenness': return betweennessCentrality(g);
            case 'closeness': return closenessCentrality(g);
            case 'eigenvector': return eigenvectorCentrality(g);
            case 'katz': return katzCentrality(g);
            case 'pagerank': return pageRank(g);
            case 'hub': return hits(g).hubs;
            case 'authority': return hits(g).authorities;
            default: return degreeCentrality(g);
        }
    }

    focusNode(id: string): void {
        this.emphasisChange.emit({ nodeIds: [id], edgeIds: [] });
    }

    runCommunities(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        let byNode: Map<string, string>;
        try {
            byNode = this.communityMethod() === 'louvain' ? louvainCommunities(g) : detectCommunities(g);
        } catch (err) {
            this.communities.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
            return;
        }
        const grouped = new Map<string, string[]>();
        for (const [node, community] of byNode) {
            const arr = grouped.get(community) ?? [];
            arr.push(node);
            grouped.set(community, arr);
        }
        const list = [...grouped.entries()]
            .map(([id, members]) => ({ id, members }))
            .sort((a, b) => b.members.length - a.members.length);
        this.communities.set(list);
        this.emphasisChange.emit({ nodeIds: [], groups: byNode });
    }

    focusCommunity(members: string[]): void {
        this.emphasisChange.emit({ nodeIds: members, edgeIds: [] });
    }

    runAllPaths(): void {
        const g = this.graph();
        if (!g || !this.pathFrom() || !this.pathTo()) return;
        this.analysisError.set('');
        const paths = allPaths(g, this.pathFrom(), this.pathTo());
        this.allPathsResult.set(paths);
        if (!paths.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set('No path connects the two nodes.');
            return;
        }
        this.emphasisChange.emit({
            nodeIds: [...new Set(paths.flatMap((p) => p.nodeIds))],
            edgeIds: [...new Set(paths.flatMap((p) => p.edgeIds))],
        });
    }

    focusAllPath(p: GraphSelection): void {
        this.emphasisChange.emit({ nodeIds: p.nodeIds, edgeIds: p.edgeIds });
    }

    runConnectedComponents(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.components.set(connectedComponents(g));
    }

    focusComponent(members: string[]): void {
        this.emphasisChange.emit({ nodeIds: members, edgeIds: [] });
    }

    // ── pattern matching (motif builder) ──

    /** Load a pattern pack's motif into the builder (a fresh copy so edits don't mutate the catalog). */
    loadPatternPack(id: string): void {
        const pack = this.patternPacks.find((p) => p.id === id) ?? null;
        this.loadedPack.set(pack);
        if (!pack) return;
        this.patternSteps.set(pack.steps.map((s) => ({ ...s })));
        this.patternMatches.set([]);
        this.analysisError.set('');
    }

    addPatternStep(): void {
        this.patternSteps.update((s) => [...s, { direction: 'out' }]);
    }

    removePatternStep(i: number): void {
        this.patternSteps.update((s) => (s.length > 1 ? s.filter((_, idx) => idx !== i) : s));
    }

    updatePatternStep(i: number, patch: Partial<PatternStep>): void {
        this.patternSteps.update((s) => s.map((step, idx) => (idx === i ? { ...step, ...patch } : step)));
    }

    runPattern(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        const matches = matchPattern(g, this.patternSteps());
        this.patternMatches.set(matches);
        if (!matches.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set('No matches for this pattern.');
            return;
        }
        this.emphasisChange.emit({
            nodeIds: [...new Set(matches.flatMap((m) => m.nodeIds))],
            edgeIds: [...new Set(matches.flatMap((m) => m.edgeIds))],
        });
    }

    focusMatch(m: GraphSelection): void {
        this.emphasisChange.emit({ nodeIds: m.nodeIds, edgeIds: m.edgeIds });
    }

    /** The node labels of a match joined into a readable chain (`Acme → Bob → Store`). */
    patternMatchLabel(m: GraphSelection): string {
        return m.nodeIds.map((id) => this.label(id)).join(' → ');
    }

    // ── V2: advanced traversal ──

    runCycles(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        const found = findCycles(g);
        this.cycles.set(found);
        if (!found.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set('No cycles in this graph.');
            return;
        }
        this.emphasisChange.emit({
            nodeIds: [...new Set(found.flatMap((c) => c.nodeIds))],
            edgeIds: [...new Set(found.flatMap((c) => c.edgeIds))],
        });
    }

    focusCycle(c: GraphSelection): void {
        this.emphasisChange.emit({ nodeIds: c.nodeIds, edgeIds: c.edgeIds });
    }

    /** A cycle rendered as a closed chain (`A → B → C → A`). */
    cycleLabel(c: GraphSelection): string {
        return [...c.nodeIds, c.nodeIds[0]].map((id) => this.label(id)).join(' → ');
    }

    runCutPoints(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        const nodes = articulationPoints(g);
        const edges = bridges(g);
        this.cutNodes.set(nodes);
        this.cutEdges.set(edges);
        if (!nodes.length && !edges.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set('No cut points — the graph has no single points of failure.');
            return;
        }
        this.emphasisChange.emit({ nodeIds: nodes, edgeIds: edges });
    }

    // ── V2: cohesive groups ──

    runCohesion(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        try {
            if (this.cohesionMetric() === 'cliques') {
                const found = cliques(g);
                this.cliquesResult.set(found);
                this.cohesionRanking.set([]);
                this.emphasisChange.emit(found.length ? { nodeIds: [...new Set(found.flat())], edgeIds: [] } : null);
                if (!found.length) this.analysisError.set('No cliques of size 3 or more.');
            } else {
                const scores = this.cohesionMetric() === 'triangles' ? triangleCount(g) : kCore(g);
                this.cohesionRanking.set(scores.slice(0, 20));
                this.cliquesResult.set([]);
                this.emphasisChange.emit(null);
            }
        } catch (err) {
            this.cohesionRanking.set([]);
            this.cliquesResult.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    focusClique(members: string[]): void {
        this.emphasisChange.emit({ nodeIds: members, edgeIds: [] });
    }

    cliqueLabel(members: string[]): string {
        return members.map((id) => this.label(id)).join(', ');
    }

    // ── V2: similarity & link prediction ──

    runSimilarity(): void {
        const g = this.graph();
        if (!g || !this.similarityFor()) return;
        this.analysisError.set('');
        this.similarityResult.set(jaccardSimilarity(g, this.similarityFor()).filter((s) => s.score > 0).slice(0, 20));
        this.emphasisChange.emit({ nodeIds: [this.similarityFor()], edgeIds: [] });
    }

    runPrediction(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        try {
            this.predictions.set(linkPrediction(g));
            if (!this.predictions().length) this.analysisError.set('No likely missing links found.');
        } catch (err) {
            this.predictions.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    focusPrediction(p: PredictedLink): void {
        this.emphasisChange.emit({ nodeIds: [p.source, p.target], edgeIds: [] });
    }

    // ── V2: flow & backbone ──

    runFlow(): void {
        const g = this.graph();
        if (!g || !this.flowFrom() || !this.flowTo()) return;
        this.analysisError.set('');
        try {
            const result = maxFlow(g, this.flowFrom(), this.flowTo());
            this.flowResult.set(result);
            this.spanningForest.set(null);
            this.emphasisChange.emit(result.minCut.edgeIds.length ? result.minCut : null);
            if (!result.value) this.analysisError.set('No flow between the two nodes.');
        } catch (err) {
            this.flowResult.set(null);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    runSpanningForest(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        const msf = maximumSpanningForest(g);
        this.spanningForest.set(msf);
        this.flowResult.set(null);
        this.emphasisChange.emit(msf.edgeIds.length ? msf : null);
    }

    // ── V2: suspicious-node scoring ──

    runScoring(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        try {
            const scores = suspicionScore(g);
            this.suspicion.set(scores.slice(0, 20));
            // Highlight the top decile (at least the top node) so the riskiest nodes stand out.
            const topN = Math.max(1, Math.round(scores.length * 0.1));
            this.emphasisChange.emit({ nodeIds: scores.slice(0, topN).map((s) => s.id), edgeIds: [] });
        } catch (err) {
            this.suspicion.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }
}
