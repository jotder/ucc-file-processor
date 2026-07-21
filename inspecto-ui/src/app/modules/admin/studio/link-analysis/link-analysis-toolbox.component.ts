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
    allPaths,
    betweennessCentrality,
    connectedComponents,
    degreeCentrality,
    detectCommunities,
    explainNode,
    louvainCommunities,
    matchPattern,
    neighborhood,
    shortestPath,
} from 'app/inspecto/graph';
import { GraphEmphasis } from 'app/modules/admin/catalog/graph-view.component';

type AnalysisTab = 'path' | 'explain' | 'centrality' | 'communities' | 'pattern' | 'all-paths' | 'components';

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
        { id: 'pattern', label: 'Pattern match', icon: 'heroicons_outline:magnifying-glass-circle' },
    ];

    /** The open tool group (accordion: one open at a time; `null` = all collapsed). */
    readonly tab = signal<AnalysisTab | null>('path');
    readonly pathFrom = signal('');
    readonly pathTo = signal('');
    readonly explainFor = signal('');
    readonly explainHops = signal(1);
    readonly centralityMetric = signal<'degree' | 'betweenness'>('degree');
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
        this.analysisError.set('');
        this.pathFrom.set('');
        this.pathTo.set('');
        this.explainFor.set('');
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
        }
    }

    // ── analysis ──

    runPath(): void {
        const g = this.graph();
        if (!g || !this.pathFrom() || !this.pathTo()) return;
        this.analysisError.set('');
        const p = shortestPath(g, this.pathFrom(), this.pathTo());
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
            const scores = this.centralityMetric() === 'degree' ? degreeCentrality(g) : betweennessCentrality(g);
            this.ranking.set(scores.slice(0, 20));
            this.emphasisChange.emit(null);
        } catch (err) {
            this.ranking.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
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
}
