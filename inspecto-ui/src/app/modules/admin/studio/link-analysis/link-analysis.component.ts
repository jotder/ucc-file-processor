import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators, AbstractControl, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule, MatMenuTrigger } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { firstValueFrom } from 'rxjs';
import { PipelineSummary, PipelinesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import {
    G6GraphData,
    GraphSelection,
    GraphSourceId,
    GraphSourceQuery,
    NodeScore,
    PatternStep,
    betweennessCentrality,
    collapseBranches,
    degreeCentrality,
    descendants,
    detectCommunities,
    explainNode,
    filterByKinds,
    isForest,
    louvainCommunities,
    matchPattern,
    neighborhood,
    searchNodes,
    shortestPath,
} from 'app/inspecto/graph';
import { ICON_COLOR_SWATCHES } from 'app/inspecto/theme/chart-tokens';
import {
    EdgePattern,
    GRAPH_EDGE_PATTERNS,
    GRAPH_EDGE_SIZES,
    GRAPH_LAYOUTS,
    GRAPH_NODE_SHAPES,
    GraphDisplayOptions,
    GraphEmphasis,
    GraphLayoutId,
    GraphViewComponent,
    baseEdgeKind,
} from 'app/modules/admin/catalog/graph-view.component';
import { ElementDetailDialog, ElementDetailResult } from 'app/inspecto/investigation';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { SAMPLE_SOURCES } from 'app/modules/admin/studio/datasets/dataset-sources';
import { ProjectedGraph } from './entity-projection';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisService, LinkAnalysisView } from './link-analysis.service';

/** Inline duplicate-name guard (house form rule) — blocks saving a view under a taken name. */
function uniqueNameValidator(taken: () => string[]): ValidatorFn {
    return (c: AbstractControl) => {
        const v = String(c.value ?? '').trim().toLowerCase();
        return taken().some((t) => t.trim().toLowerCase() === v) ? { duplicate: true } : null;
    };
}

type AnalysisTab = 'path' | 'explain' | 'centrality' | 'communities' | 'pattern';

/** One line of the collapsed-query status (left-pane summary + the top status bar chips). */
interface QuerySummaryItem {
    icon: string;
    label: string;
    value: string;
}

/**
 * **Link Analysis Studio** (C5, plan: docs/superpower/link-analysis-studio-plan.md §3 P3) — pick a
 * GraphSource, shape a query, render through the shared {@link GraphViewComponent}, analyze with the
 * pure `graph-analysis` library, and save the investigation as a `link-analysis-view` Component.
 */
@Component({
    selector: 'inspecto-link-analysis',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        DecimalPipe, ReactiveFormsModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatDialogModule,
        MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule, MatSelectModule, MatTooltipModule,
        InspectoAlertComponent, InspectoEmptyStateComponent, InspectoSkeletonComponent, GraphViewComponent,
        DataTableComponent,
    ],
    templateUrl: './link-analysis.component.html',
    host: { '(document:fullscreenchange)': 'onFullscreenChange()' },
})
export class LinkAnalysisComponent implements OnInit {
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private graphSources = inject(GraphSourcesService);
    private datasetsService = inject(DatasetsService);
    private pipelinesService = inject(PipelinesService);
    private viewsService = inject(LinkAnalysisService);

    readonly sources = this.graphSources.sources;

    @ViewChild(GraphViewComponent) private graphView?: GraphViewComponent;
    @ViewChild('studioRoot') private studioRoot?: ElementRef<HTMLElement>;
    @ViewChild('canvasZone') private canvasZone?: ElementRef<HTMLElement>;
    @ViewChild('saveTrigger') private saveTrigger?: MatMenuTrigger;

    // ── workspace layout: canvas-first; a single bottom panel holds Query / Analysis / Data ──
    /** Full query form vs its collapsed selected-values summary (auto-collapses after a run). */
    readonly queryOpen = signal(true);

    /** The analysis tool groups (the Analysis-tab accordion = the graph-algorithms toolbox). */
    readonly tools: { id: AnalysisTab; label: string; icon: string }[] = [
        { id: 'path', label: 'Shortest path', icon: 'heroicons_outline:arrows-right-left' },
        { id: 'explain', label: 'Explain node', icon: 'heroicons_outline:light-bulb' },
        { id: 'centrality', label: 'Centrality', icon: 'heroicons_outline:star' },
        { id: 'communities', label: 'Communities', icon: 'heroicons_outline:user-group' },
        { id: 'pattern', label: 'Pattern match', icon: 'heroicons_outline:magnifying-glass-circle' },
    ];

    // ── query builder ──
    readonly sourceId = signal<GraphSourceId>('entity-projection');
    readonly datasets = signal<Dataset[]>([]);
    readonly pipelines = signal<PipelineSummary[]>([]);
    readonly queryForm = this.fb.nonNullable.group({
        from: [''],
        depth: [2],
        direction: ['both' as 'out' | 'in' | 'both'],
        pipeline: [''],
        counts: [false],
        datasetId: [''],
        sourceCol: [''],
        targetCol: [''],
        linkKindCol: [''],
    });

    /** Columns offered by the projection mapping selects — the picked Dataset's columns (or its sample rows'). */
    readonly datasetColumns = signal<string[]>([]);

    // ── result state ──
    readonly loading = signal(false);
    readonly loadError = signal('');
    readonly graph = signal<G6GraphData | null>(null);
    readonly truncated = signal(false);
    /** The query behind the rendered graph — what the collapsed form + status bar summarize. */
    readonly lastRun = signal<{ sourceId: GraphSourceId; query: GraphSourceQuery } | null>(null);

    readonly sourceLabel = computed<string>(() => {
        const run = this.lastRun();
        return run ? (this.sources.find((s) => s.id === run.sourceId)?.label ?? run.sourceId) : '';
    });
    readonly querySummary = computed<QuerySummaryItem[]>(() => {
        const run = this.lastRun();
        if (!run) return [];
        const q = run.query;
        switch (run.sourceId) {
            case 'entity-projection': {
                const p = q.projection;
                if (!p) return [];
                const ds = this.datasets().find((d) => d.id === p.datasetId);
                const items: QuerySummaryItem[] = [
                    { icon: 'heroicons_outline:table-cells', label: 'Dataset', value: ds?.name ?? p.datasetId },
                    { icon: 'heroicons_outline:arrow-long-right', label: 'Mapping', value: `${p.sourceCol} → ${p.targetCol}` },
                ];
                if (p.linkKindCol) items.push({ icon: 'heroicons_outline:hashtag', label: 'Link type', value: p.linkKindCol });
                return items;
            }
            case 'lineage':
                return [
                    { icon: 'heroicons_outline:viewfinder-circle', label: 'Root', value: q.from || 'whole graph' },
                    { icon: 'heroicons_outline:hashtag', label: 'Depth', value: String(q.depth ?? 2) },
                    {
                        icon: 'heroicons_outline:arrows-right-left', label: 'Direction',
                        value: q.direction === 'in' ? 'Upstream' : q.direction === 'out' ? 'Downstream' : 'Both',
                    },
                ];
            case 'provenance': {
                const items: QuerySummaryItem[] = [{ icon: 'heroicons_outline:queue-list', label: 'Pipeline', value: q.from ?? '' }];
                if (q.counts) items.push({ icon: 'heroicons_outline:chart-bar', label: 'Edges', value: 'weighted by record counts' });
                return items;
            }
            default:
                return [{ icon: 'heroicons_outline:share', label: 'Scope', value: 'every registered component' }];
        }
    });

    // ── search & kind filtering ──
    readonly search = signal('');
    readonly kindFilter = signal<string[]>([]); // empty = all
    readonly nodeKinds = computed<string[]>(() => {
        const g = this.graph();
        return g ? [...new Set(g.nodes.map((n) => n.data.kind))].sort() : [];
    });
    readonly edgeKinds = computed<string[]>(() => {
        const g = this.graph();
        return g ? [...new Set(g.edges.map((e) => baseEdgeKind(e.data.kind)))].sort() : [];
    });
    /** Collapsed branch roots — their downstream nodes are hidden until expanded. */
    readonly collapsedRoots = signal<string[]>([]);
    /** The kind-filtered graph BEFORE branch collapsing (collapse/expand decisions read this). */
    private readonly baseGraph = computed<G6GraphData | null>(() => {
        const g = this.graph();
        return g ? filterByKinds(g, this.kindFilter(), []) : null;
    });
    readonly displayed = computed<G6GraphData | null>(() => {
        const g = this.baseGraph();
        return g ? collapseBranches(g, this.collapsedRoots()) : null;
    });

    // ── display options (persisted with a saved view; applied on load) ──
    readonly nodeLabels = signal(true);
    readonly edgeLabels = signal(true);
    readonly nodeColors = signal<Record<string, string>>({});
    readonly edgeColors = signal<Record<string, string>>({});
    readonly nodeShapes = signal<Record<string, string>>({});
    readonly edgePatterns = signal<Record<string, EdgePattern>>({});
    readonly edgeSizes = signal<Record<string, number>>({});
    readonly displayOptions = computed<GraphDisplayOptions>(() => ({
        nodeLabels: this.nodeLabels(),
        edgeLabels: this.edgeLabels(),
        nodeColors: this.nodeColors(),
        edgeColors: this.edgeColors(),
        nodeShapes: this.nodeShapes(),
        edgePatterns: this.edgePatterns(),
        edgeSizes: this.edgeSizes(),
    }));
    readonly swatches = ICON_COLOR_SWATCHES;
    readonly shapeOptions = GRAPH_NODE_SHAPES;
    readonly patternOptions = GRAPH_EDGE_PATTERNS;
    readonly sizeOptions = GRAPH_EDGE_SIZES;
    /** True when any display option deviates from the defaults (tints the paint-brush button). */
    readonly displayCustomized = computed<boolean>(
        () => !this.nodeLabels() || !this.edgeLabels()
            || Object.keys(this.nodeColors()).length > 0 || Object.keys(this.edgeColors()).length > 0
            || Object.keys(this.nodeShapes()).length > 0 || Object.keys(this.edgePatterns()).length > 0
            || Object.keys(this.edgeSizes()).length > 0,
    );

    // ── layout (the Layout toolbox; persisted with a saved view) ──
    readonly layoutId = signal<GraphLayoutId>('dagre');
    readonly layouts = GRAPH_LAYOUTS;
    /** Whether the current graph is tree/forest-shaped — gates the tree layouts. */
    readonly isTreeShaped = computed<boolean>(() => {
        const g = this.displayed();
        return !!g && isForest(g);
    });

    // ── fullscreen (whole studio or just the canvas zone) + bottom panel (Query/Analysis/Data) ──
    readonly fullscreen = signal<'app' | 'graph' | null>(null);
    readonly bottomOpen = signal(true);
    readonly bottomTab = signal<'query' | 'analysis' | 'data'>('query');
    readonly tableMode = signal<'links' | 'nodes'>('links');
    /** The displayed graph as rows — search-narrowed, so canvas and table show the same result. */
    readonly tableRows = computed<Record<string, unknown>[]>(() => {
        const g = this.displayed();
        if (!g) return [];
        const q = this.search().trim();
        const match = q ? new Set(searchNodes(g, q)) : null;
        if (this.tableMode() === 'nodes') {
            return g.nodes
                .filter((n) => !match || match.has(n.id))
                .map((n) => ({
                    label: n.data.label,
                    kind: n.data.kind,
                    links: g.edges.filter((e) => e.source === n.id || e.target === n.id).length,
                    id: n.id,
                }));
        }
        const label = (id: string): string => g.nodes.find((n) => n.id === id)?.data.label ?? id;
        return g.edges
            .filter((e) => !match || match.has(e.source) || match.has(e.target))
            .map((e) => ({
                source: label(e.source),
                relationship: baseEdgeKind(e.data.kind),
                target: label(e.target),
                rows: (e.data as { count?: number }).count ?? 1,
                id: e.id,
            }));
    });
    readonly nodeOptions = computed(() => {
        const g = this.displayed();
        return (g?.nodes ?? [])
            .map((n) => ({ id: n.id, label: n.data.label }))
            .sort((a, b) => a.label.localeCompare(b.label))
            .slice(0, 500);
    });

    // ── analysis ──
    /** The open tool group (accordion: one open at a time; `null` = all collapsed). */
    readonly tab = signal<AnalysisTab | null>('path');
    readonly pathFrom = signal('');
    readonly pathTo = signal('');
    readonly explainFor = signal('');
    readonly explainHops = signal(1);
    readonly centralityMetric = signal<'degree' | 'betweenness'>('degree');
    readonly analysisError = signal('');
    readonly emphasis = signal<GraphEmphasis | null>(null);
    readonly pathResult = signal<{ hops: string[] } | null>(null);
    readonly explainText = signal('');
    readonly ranking = signal<NodeScore[]>([]);
    readonly communityMethod = signal<'label-prop' | 'louvain'>('label-prop');
    readonly communities = signal<{ id: string; members: string[] }[]>([]);
    /** The pattern-match motif — step 0 = the start node; each later step traverses one edge. */
    readonly patternSteps = signal<PatternStep[]>([{}, { direction: 'out' }]);
    readonly patternMatches = signal<GraphSelection[]>([]);

    // ── saved views ──
    readonly views = signal<LinkAnalysisView[]>([]);
    readonly saveForm = this.fb.nonNullable.group({
        name: ['', [Validators.required, uniqueNameValidator(() => this.views().map((v) => v.name))]],
        description: [''],
    });
    readonly saving = signal(false);

    ngOnInit(): void {
        // Each list degrades independently — a failing lookup must not blank the pane.
        this.datasetsService.list().subscribe({ next: (d) => this.datasets.set(d), error: () => undefined });
        this.pipelinesService.list().subscribe({ next: (p) => this.pipelines.set(p), error: () => undefined });
        this.viewsService.list().subscribe({ next: (v) => this.views.set(v), error: () => undefined });
        this.queryForm.controls.datasetId.valueChanges.subscribe((id) => this.onDatasetPicked(id));
    }

    labelOf(id: string): string {
        return this.graph()?.nodes.find((n) => n.id === id)?.data.label ?? id;
    }

    private onDatasetPicked(id: string): void {
        const ds = this.datasets().find((d) => d.id === id);
        if (!ds) {
            this.datasetColumns.set([]);
            return;
        }
        const declared = ds.columns.map((c) => c.name);
        const sampled = Object.keys(SAMPLE_SOURCES[ds.sourceName]?.[0] ?? {});
        this.datasetColumns.set(declared.length ? declared : sampled);
    }

    /** The query the current form + source amounts to (also what a saved view persists). */
    private buildQuery(): GraphSourceQuery | { error: string } {
        const f = this.queryForm.getRawValue();
        switch (this.sourceId()) {
            case 'entity-projection':
                if (!f.datasetId || !f.sourceCol || !f.targetCol) {
                    return { error: 'Pick a dataset plus its source and target columns.' };
                }
                return {
                    projection: {
                        datasetId: f.datasetId, sourceCol: f.sourceCol, targetCol: f.targetCol,
                        linkKindCol: f.linkKindCol || undefined,
                    },
                };
            case 'provenance':
                if (!f.pipeline) return { error: 'Pick a pipeline.' };
                return { from: f.pipeline, counts: f.counts };
            case 'lineage':
                return { from: f.from || undefined, depth: f.depth, direction: f.direction };
            default:
                return {};
        }
    }

    async run(): Promise<void> {
        const q = this.buildQuery();
        if ('error' in q) {
            this.loadError.set(q.error);
            return;
        }
        await this.execute(this.sourceId(), q);
    }

    private async execute(sourceId: GraphSourceId, q: GraphSourceQuery): Promise<void> {
        const source = this.graphSources.byId(sourceId);
        if (!source) return;
        this.loading.set(true);
        this.loadError.set('');
        this.resetAnalysis();
        try {
            const g = await source.query(q);
            this.graph.set(g);
            this.truncated.set(!!(g as ProjectedGraph).truncated);
            this.kindFilter.set([]);
            this.lastRun.set({ sourceId, query: q });
            this.queryOpen.set(false); // smart form: collapse to the selected-values summary
        } catch (err) {
            this.graph.set(null);
            this.lastRun.set(null);
            this.queryOpen.set(true); // a failing query needs its form back
            this.loadError.set(err instanceof Error ? err.message : apiErrorMessage(err, 'The graph query failed.'));
        } finally {
            this.loading.set(false);
        }
    }

    // ── workspace layout ──

    /** Reopen the full query form (status-bar pencil) in the bottom panel's Query tab. */
    editQuery(): void {
        this.bottomOpen.set(true);
        this.bottomTab.set('query');
        this.queryOpen.set(true);
    }

    /** Open the graph-algorithms toolbox (the bottom panel's Analysis tab). */
    openAnalysis(): void {
        this.bottomOpen.set(true);
        this.bottomTab.set('analysis');
    }

    /** Switch the bottom panel to a tab, opening it if collapsed. */
    selectBottomTab(tab: 'query' | 'analysis' | 'data'): void {
        this.bottomTab.set(tab);
        this.bottomOpen.set(true);
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
                return this.explainText() ? this.labelOf(this.explainFor()) : '';
            case 'centrality':
                return this.ranking().length ? `top ${this.ranking().length}` : '';
            case 'communities':
                return this.communities().length ? `${this.communities().length} found` : '';
            case 'pattern':
                return this.patternMatches().length ? `${this.patternMatches().length} matches` : '';
        }
    }

    private resetAnalysis(): void {
        this.emphasis.set(null);
        this.pathResult.set(null);
        this.explainText.set('');
        this.ranking.set([]);
        this.communities.set([]);
        this.patternMatches.set([]);
        this.analysisError.set('');
        this.pathFrom.set('');
        this.pathTo.set('');
        this.explainFor.set('');
        this.search.set('');
        this.collapsedRoots.set([]);
    }

    // ── display options ──

    /** Pick (or with `null` clear) the stroke colour for a node kind. */
    setNodeColor(kind: string, color: string | null): void {
        const { [kind]: _old, ...rest } = this.nodeColors();
        this.nodeColors.set(color ? { ...rest, [kind]: color } : rest);
    }

    /** Pick (or with `null` clear) the stroke colour for a relationship kind. */
    setEdgeColor(kind: string, color: string | null): void {
        const { [kind]: _old, ...rest } = this.edgeColors();
        this.edgeColors.set(color ? { ...rest, [kind]: color } : rest);
    }

    /** Pick (or with `null` clear) the shape ("icon") for a node kind. */
    setNodeShape(kind: string, shape: string | null): void {
        const { [kind]: _old, ...rest } = this.nodeShapes();
        this.nodeShapes.set(shape ? { ...rest, [kind]: shape } : rest);
    }

    /** Pick (or with `null` clear) the line pattern for a relationship kind. */
    setEdgePattern(kind: string, pattern: EdgePattern | null): void {
        const { [kind]: _old, ...rest } = this.edgePatterns();
        this.edgePatterns.set(pattern ? { ...rest, [kind]: pattern } : rest);
    }

    /** Pick (or with `null` clear) the line width for a relationship kind. */
    setEdgeSize(kind: string, size: number | null): void {
        const { [kind]: _old, ...rest } = this.edgeSizes();
        this.edgeSizes.set(size != null ? { ...rest, [kind]: size } : rest);
    }

    /** Pick a graph layout (tree layouts are gated on {@link isTreeShaped} in the template). */
    setLayout(id: GraphLayoutId): void {
        this.layoutId.set(id);
    }

    private applyDisplay(display: GraphDisplayOptions | undefined): void {
        this.nodeLabels.set(display?.nodeLabels ?? true);
        this.edgeLabels.set(display?.edgeLabels ?? true);
        this.nodeColors.set(display?.nodeColors ?? {});
        this.edgeColors.set(display?.edgeColors ?? {});
        this.nodeShapes.set(display?.nodeShapes ?? {});
        this.edgePatterns.set(display?.edgePatterns ?? {});
        this.edgeSizes.set(display?.edgeSizes ?? {});
    }

    // ── canvas tools: fit · fullscreen · collapse/expand ──

    fitToScreen(): void {
        this.graphView?.fitView();
    }

    /** Toggle fullscreen for the whole studio (`app`) or just the canvas zone (`graph`). */
    toggleFullscreen(zone: 'app' | 'graph'): void {
        const el = (zone === 'app' ? this.studioRoot : this.canvasZone)?.nativeElement;
        if (!el?.requestFullscreen) return; // unsupported environment (e.g. jsdom)
        if (document.fullscreenElement) void document.exitFullscreen();
        else void el.requestFullscreen();
    }

    onFullscreenChange(): void {
        const fs = document.fullscreenElement;
        this.fullscreen.set(
            fs && fs === this.studioRoot?.nativeElement ? 'app'
            : fs && fs === this.canvasZone?.nativeElement ? 'graph'
            : null,
        );
    }

    /** Hide everything downstream of the node (the detail popup's Collapse branch). */
    collapseBranch(id: string): void {
        if (!this.collapsedRoots().includes(id)) this.collapsedRoots.set([...this.collapsedRoots(), id]);
    }

    expandBranch(id: string): void {
        this.collapsedRoots.set(this.collapsedRoots().filter((r) => r !== id));
    }

    expandAll(): void {
        this.collapsedRoots.set([]);
    }

    // ── element details (canvas click → full-detail popup) ──

    onNodeClick(id: string): void {
        const g = this.baseGraph();
        const node = g?.nodes.find((n) => n.id === id);
        if (!g || !node) return;
        const outgoing = g.edges.filter((e) => e.source === id);
        const incoming = g.edges.filter((e) => e.target === id);
        const neighbors = [...new Set([...outgoing.map((e) => e.target), ...incoming.map((e) => e.source)])]
            .map((n) => this.labelOf(n));
        const collapsed = this.collapsedRoots().includes(id);
        this.dialog
            .open(ElementDetailDialog, {
                width: '28rem',
                data: {
                    title: node.data.label,
                    subtitle: node.data.kind,
                    rows: [
                        { label: 'ID', value: id },
                        { label: 'Links', value: `${outgoing.length + incoming.length} (${outgoing.length} out · ${incoming.length} in)` },
                        { label: 'Neighbors', value: neighbors.slice(0, 8).join(', ') + (neighbors.length > 8 ? ` … +${neighbors.length - 8}` : '') },
                    ],
                    branch: collapsed ? 'expand' : descendants(g, id).size ? 'collapse' : undefined,
                },
            })
            .afterClosed()
            .subscribe((action: ElementDetailResult) => {
                if (action === 'focus') this.focusNode(id);
                else if (action === 'collapse') this.collapseBranch(id);
                else if (action === 'expand') this.expandBranch(id);
            });
    }

    onEdgeClick(id: string): void {
        const g = this.displayed();
        const edge = g?.edges.find((e) => e.id === id);
        if (!g || !edge) return;
        const count = (edge.data as { count?: number }).count;
        this.dialog
            .open(ElementDetailDialog, {
                width: '28rem',
                data: {
                    title: `${this.labelOf(edge.source)} → ${this.labelOf(edge.target)}`,
                    subtitle: baseEdgeKind(edge.data.kind),
                    rows: [
                        { label: 'From', value: this.labelOf(edge.source) },
                        { label: 'To', value: this.labelOf(edge.target) },
                        { label: 'Relationship', value: baseEdgeKind(edge.data.kind) },
                        ...(count ? [{ label: 'Folded rows', value: String(count) }] : []),
                        { label: 'ID', value: id },
                    ],
                },
            })
            .afterClosed()
            .subscribe((action: ElementDetailResult) => {
                if (action === 'focus') this.emphasis.set({ nodeIds: [edge.source, edge.target], edgeIds: [id] });
            });
    }

    /** Bottom-panel row click — focus the element on the canvas (rows carry their graph id). */
    onTableRow(row: Record<string, unknown>): void {
        const id = String(row['id'] ?? '');
        if (!id) return;
        if (this.tableMode() === 'nodes') {
            this.focusNode(id);
            return;
        }
        const edge = this.displayed()?.edges.find((e) => e.id === id);
        if (edge) this.emphasis.set({ nodeIds: [edge.source, edge.target], edgeIds: [id] });
    }

    // ── search & filters ──

    onSearch(text: string): void {
        this.search.set(text);
        const g = this.displayed();
        if (!g || !text.trim()) {
            this.emphasis.set(null);
            return;
        }
        this.emphasis.set({ nodeIds: searchNodes(g, text), edgeIds: [] });
    }

    toggleKind(kind: string, on: boolean): void {
        const all = this.nodeKinds();
        const current = this.kindFilter().length ? this.kindFilter() : all;
        const next = on ? [...new Set([...current, kind])] : current.filter((k) => k !== kind);
        this.kindFilter.set(next.length === all.length ? [] : next);
    }

    kindOn(kind: string): boolean {
        return !this.kindFilter().length || this.kindFilter().includes(kind);
    }

    clearFilters(): void {
        this.kindFilter.set([]);
        this.search.set('');
        this.emphasis.set(null);
    }

    // ── analysis ──

    runPath(): void {
        const g = this.displayed();
        if (!g || !this.pathFrom() || !this.pathTo()) return;
        this.analysisError.set('');
        const p = shortestPath(g, this.pathFrom(), this.pathTo());
        if (!p) {
            this.pathResult.set(null);
            this.emphasis.set(null);
            this.analysisError.set('No path connects the two nodes.');
            return;
        }
        this.pathResult.set({ hops: p.nodeIds });
        this.emphasis.set({ nodeIds: p.nodeIds, edgeIds: p.edgeIds });
    }

    runExplain(): void {
        const g = this.displayed();
        const id = this.explainFor();
        if (!g || !id) return;
        const nb = neighborhood(g, id, this.explainHops());
        this.explainText.set(explainNode(g, id));
        this.emphasis.set({ nodeIds: nb.nodes.map((n) => n.id), edgeIds: nb.edges.map((e) => e.id) });
    }

    runCentrality(): void {
        const g = this.displayed();
        if (!g) return;
        this.analysisError.set('');
        try {
            const scores = this.centralityMetric() === 'degree' ? degreeCentrality(g) : betweennessCentrality(g);
            this.ranking.set(scores.slice(0, 20));
            this.emphasis.set(null);
        } catch (err) {
            this.ranking.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    focusNode(id: string): void {
        this.emphasis.set({ nodeIds: [id], edgeIds: [] });
    }

    runCommunities(): void {
        const g = this.displayed();
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
        this.emphasis.set({ nodeIds: [], groups: byNode });
    }

    focusCommunity(members: string[]): void {
        this.emphasis.set({ nodeIds: members, edgeIds: [] });
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
        const g = this.displayed();
        if (!g) return;
        this.analysisError.set('');
        const matches = matchPattern(g, this.patternSteps());
        this.patternMatches.set(matches);
        if (!matches.length) {
            this.emphasis.set(null);
            this.analysisError.set('No matches for this pattern.');
            return;
        }
        this.emphasis.set({
            nodeIds: [...new Set(matches.flatMap((m) => m.nodeIds))],
            edgeIds: [...new Set(matches.flatMap((m) => m.edgeIds))],
        });
    }

    focusMatch(m: GraphSelection): void {
        this.emphasis.set({ nodeIds: m.nodeIds, edgeIds: m.edgeIds });
    }

    /** The node labels of a match joined into a readable chain (`Acme → Bob → Store`). */
    patternMatchLabel(m: GraphSelection): string {
        return m.nodeIds.map((id) => this.labelOf(id)).join(' → ');
    }

    // ── export ──

    /** Download the displayed graph as `link-analysis.json` (the shared G6GraphData shape). */
    exportJson(): void {
        const g = this.displayed();
        if (!g) return;
        this.download(URL.createObjectURL(new Blob([JSON.stringify(g, null, 2)], { type: 'application/json' })), 'link-analysis.json');
    }

    /** Download the rendered canvas as `link-analysis.png`. */
    async exportPng(): Promise<void> {
        const dataUri = await this.graphView?.exportPng();
        if (dataUri) this.download(dataUri, 'link-analysis.png');
        else this.toastr.warning('Nothing rendered to export yet.');
    }

    private download(href: string, filename: string): void {
        const a = document.createElement('a');
        a.href = href;
        a.download = filename;
        a.click();
        if (href.startsWith('blob:')) URL.revokeObjectURL(href);
    }

    // ── saved views ──

    async saveView(): Promise<void> {
        this.saveForm.markAllAsTouched();
        if (this.saveForm.invalid) return;
        const q = this.buildQuery();
        if ('error' in q) {
            this.loadError.set(q.error);
            return;
        }
        const { name, description } = this.saveForm.getRawValue();
        const view: LinkAnalysisView = {
            id: name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-'),
            name: name.trim(),
            description: description.trim() || undefined,
            sourceId: this.sourceId(),
            query: q,
            display: this.displayOptions(), // styling travels with the view; reapplied on load
            layout: this.layoutId(),
        };
        this.saving.set(true);
        try {
            await firstValueFrom(this.viewsService.save(view));
            this.views.set([...this.views().filter((v) => v.id !== view.id), view]);
            this.saveForm.reset({ name: '', description: '' });
            this.saveTrigger?.closeMenu();
            this.toastr.success(`Saved “${view.name}”.`);
        } catch (err) {
            this.toastr.error(apiErrorMessage(err, 'Saving the view failed.'));
        } finally {
            this.saving.set(false);
        }
    }

    async loadView(view: LinkAnalysisView): Promise<void> {
        this.sourceId.set(view.sourceId);
        this.applyDisplay(view.display);
        this.layoutId.set(view.layout ?? 'dagre');
        const p = view.query.projection;
        this.queryForm.patchValue({
            from: view.query.from ?? '',
            depth: view.query.depth ?? 2,
            direction: view.query.direction ?? 'both',
            pipeline: view.sourceId === 'provenance' ? (view.query.from ?? '') : '',
            counts: view.query.counts ?? false,
            datasetId: p?.datasetId ?? '',
            sourceCol: p?.sourceCol ?? '',
            targetCol: p?.targetCol ?? '',
            linkKindCol: p?.linkKindCol ?? '',
        });
        await this.execute(view.sourceId, view.query);
    }
}
