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
import { MatSliderModule } from '@angular/material/slider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { firstValueFrom } from 'rxjs';
import { PipelineSummary, PipelinesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import {
    G6GraphData,
    GraphSourceId,
    GraphSourceQuery,
    HistoryStack,
    collapseBranches,
    descendants,
    filterByKinds,
    filterByTime,
    isForest,
    emptyHistory,
    mergeGraphs,
    pushHistory,
    redo as redoHistory,
    searchNodes,
    toGraphml,
    undo as undoHistory,
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
import { ElementDetailDialog, ElementDetailResult, ElementObjectRef, PivotService } from 'app/inspecto/investigation';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { ProjectedGraph } from './entity-projection';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisService, LinkAnalysisView } from './link-analysis.service';
import { LinkAnalysisToolboxComponent } from './link-analysis-toolbox.component';
import { LinkAnalysisQueryPanelComponent, QuerySummaryItem } from './link-analysis-query-panel.component';

/** Inline duplicate-name guard (house form rule) — blocks saving a view under a taken name. */
function uniqueNameValidator(taken: () => string[]): ValidatorFn {
    return (c: AbstractControl) => {
        const v = String(c.value ?? '').trim().toLowerCase();
        return taken().some((t) => t.trim().toLowerCase() === v) ? { duplicate: true } : null;
    };
}

/** Undo/redo scope (Phase G): presentation state only — search/filter, display options, layout, and
 *  collapsed branches. NOT the query or the loaded graph itself (a heavier, separate feature). */
interface PresentationSnapshot {
    search: string;
    kindFilter: string[];
    timeColumn: string;
    timeCutoff: number | null;
    collapsedRoots: string[];
    nodeLabels: boolean;
    edgeLabels: boolean;
    nodeColors: Record<string, string>;
    edgeColors: Record<string, string>;
    nodeShapes: Record<string, string>;
    edgePatterns: Record<string, EdgePattern>;
    edgeSizes: Record<string, number>;
    layoutId: GraphLayoutId;
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
        ReactiveFormsModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatDialogModule,
        MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule, MatSelectModule, MatSliderModule,
        MatTooltipModule,
        InspectoAlertComponent, InspectoEmptyStateComponent, InspectoSkeletonComponent, GraphViewComponent,
        DataTableComponent, TransferMenuComponent, LinkAnalysisToolboxComponent, LinkAnalysisQueryPanelComponent,
    ],
    templateUrl: './link-analysis.component.html',
    host: {
        '(document:fullscreenchange)': 'onFullscreenChange()',
        '(document:keydown)': 'onHistoryKeydown($event)',
    },
})
export class LinkAnalysisComponent implements OnInit {
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private route = inject(ActivatedRoute);
    private pivotService = inject(PivotService);
    private graphSources = inject(GraphSourcesService);
    private datasetsService = inject(DatasetsService);
    private pipelinesService = inject(PipelinesService);
    private viewsService = inject(LinkAnalysisService);

    readonly sources = this.graphSources.sources;

    @ViewChild(GraphViewComponent) private graphView?: GraphViewComponent;
    @ViewChild(LinkAnalysisToolboxComponent) private toolbox?: LinkAnalysisToolboxComponent;
    @ViewChild(LinkAnalysisQueryPanelComponent) private queryPanel?: LinkAnalysisQueryPanelComponent;
    @ViewChild('studioRoot') private studioRoot?: ElementRef<HTMLElement>;
    @ViewChild('canvasZone') private canvasZone?: ElementRef<HTMLElement>;
    @ViewChild('saveTrigger') private saveTrigger?: MatMenuTrigger;

    // ── workspace layout: canvas-first; a single bottom panel holds Query / Analysis / Data ──
    /** Full query form vs its collapsed selected-values summary (auto-collapses after a run). */
    readonly queryOpen = signal(true);

    // ── query source (the form itself lives in the query-panel child) ──
    readonly sourceId = signal<GraphSourceId>('entity-projection');
    readonly datasets = signal<Dataset[]>([]);
    readonly pipelines = signal<PipelineSummary[]>([]);

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
                if (q.projections?.length) {
                    return q.projections.map((p) => {
                        const ds = this.datasets().find((d) => d.id === p.datasetId);
                        return {
                            icon: 'heroicons_outline:table-cells',
                            label: p.entityType ?? 'Mapping',
                            value: `${ds?.name ?? p.datasetId}: ${p.sourceCol} → ${p.targetCol}`,
                        };
                    });
                }
                const p = q.projection;
                if (!p) return [];
                const ds = this.datasets().find((d) => d.id === p.datasetId);
                const items: QuerySummaryItem[] = [
                    { icon: 'heroicons_outline:table-cells', label: 'Dataset', value: ds?.name ?? p.datasetId },
                    { icon: 'heroicons_outline:arrow-long-right', label: 'Mapping', value: `${p.sourceCol} → ${p.targetCol}` },
                ];
                if (p.linkKindCol) items.push({ icon: 'heroicons_outline:hashtag', label: 'Link type', value: p.linkKindCol });
                if (p.attrCols?.length) items.push({ icon: 'heroicons_outline:tag', label: 'Attributes', value: p.attrCols.join(', ') });
                return items;
            }
            case 'lineage':
                return [
                    {
                        icon: 'heroicons_outline:viewfinder-circle', label: q.roots?.length ? 'Roots' : 'Root',
                        value: q.roots?.length ? q.roots.join(', ') : (q.from || 'whole graph'),
                    },
                    { icon: 'heroicons_outline:hashtag', label: 'Depth', value: String(q.depth ?? 2) },
                    {
                        icon: 'heroicons_outline:arrows-right-left', label: 'Direction',
                        value: q.direction === 'in' ? 'Upstream' : q.direction === 'out' ? 'Downstream' : 'Both',
                    },
                ];
            case 'provenance': {
                const items: QuerySummaryItem[] = [{
                    icon: 'heroicons_outline:queue-list', label: q.roots?.length ? 'Pipelines' : 'Pipeline',
                    value: q.roots?.length ? q.roots.join(', ') : (q.from ?? ''),
                }];
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

    // ── timeline filter (BACKLOG V2 §3: a time slider filtering edges by a temporal `attrs` column) ──
    /** The `attrs` column to filter on; '' = timeline off. */
    readonly timeColumn = signal('');
    /** Cutoff (epoch millis) — edges dated after this are hidden; `null` until the slider is touched. */
    readonly timeCutoff = signal<number | null>(null);
    /** Every edge-`attrs` key present anywhere in the loaded graph — the column picker's options. */
    readonly attrColumns = computed<string[]>(() => {
        const g = this.graph();
        if (!g) return [];
        return [...new Set(g.edges.flatMap((e) => Object.keys(e.data.attrs ?? {})))].sort();
    });
    /** [min, max] epoch millis of every parseable date in the selected column — the slider's rail. */
    readonly timeExtent = computed<[number, number] | null>(() => {
        const g = this.graph();
        const col = this.timeColumn();
        if (!g || !col) return null;
        const times = g.edges
            .map((e) => Date.parse(e.data.attrs?.[col] ?? ''))
            .filter((t) => Number.isFinite(t));
        return times.length ? [Math.min(...times), Math.max(...times)] : null;
    });
    /** The kind- and time-filtered graph BEFORE branch collapsing (collapse/expand decisions read this). */
    private readonly baseGraph = computed<G6GraphData | null>(() => {
        const g = this.graph();
        if (!g) return null;
        const kindFiltered = filterByKinds(g, this.kindFilter(), []);
        const col = this.timeColumn();
        const cutoff = this.timeCutoff();
        return col && cutoff != null ? filterByTime(kindFiltered, col, cutoff) : kindFiltered;
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

    // ── canvas emphasis (shared: written by search / canvas clicks / the analysis toolbox child) ──
    readonly emphasis = signal<GraphEmphasis | null>(null);

    // ── saved views ──
    readonly views = signal<LinkAnalysisView[]>([]);

    /** The saved Link Analysis views as transfer references — what the export/import menu offers. */
    readonly transferItems = computed(() => this.views().map((v) => ({ kind: 'link-analysis-view' as const, id: v.id })));

    readonly saveForm = this.fb.nonNullable.group({
        name: ['', [Validators.required, uniqueNameValidator(() => this.views().map((v) => v.name))]],
        description: [''],
    });
    readonly saving = signal(false);

    /** An incoming investigation pivot (ui-design-review R8) awaiting a graph to resolve against —
     *  cleared after the first query run, found or not. */
    private pendingPivot?: ElementObjectRef;

    ngOnInit(): void {
        // Each list degrades independently — a failing lookup must not blank the pane.
        this.datasetsService.list().subscribe({ next: (d) => this.datasets.set(d), error: () => undefined });
        this.pipelinesService.list().subscribe({ next: (p) => this.pipelines.set(p), error: () => undefined });
        this.viewsService.list().subscribe({ next: (v) => this.views.set(v), error: () => undefined });
        this.pendingPivot = this.pivotService.readIncoming(this.route);
    }

    /** Try to find the pivoted-in record among the just-loaded graph's nodes; focus it if present,
     *  else toast that this view doesn't have it. Runs once, after the first graph load. */
    private resolvePendingPivot(g: G6GraphData): void {
        const pivot = this.pendingPivot;
        if (!pivot) return;
        this.pendingPivot = undefined;
        const node = g.nodes.find((n) => n.data.objectRef?.id === pivot.id && n.data.objectRef?.type === pivot.type);
        if (node) this.focusNode(node.id);
        else this.toastr.info('That record is not in this graph.', 'Not found');
    }

    /** Re-fetch the saved views (after an import brought some in). */
    reloadViews(): void {
        this.viewsService.list().subscribe({ next: (v) => this.views.set(v), error: () => undefined });
    }

    /** Node-id → label over the full loaded graph. An arrow field so it can be passed to the toolbox
     *  child as an `[labelOf]` input without losing its `this` binding. */
    readonly labelOf = (id: string): string =>
        this.graph()?.nodes.find((n) => n.id === id)?.data.label ?? id;

    async run(): Promise<void> {
        const q = this.queryPanel?.buildQuery() ?? { error: 'The query form is not ready.' };
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
        // A fresh graph clears the canvas emphasis + presentation filters and the analysis toolbox's results.
        this.emphasis.set(null);
        this.toolbox?.reset();
        this.search.set('');
        this.collapsedRoots.set([]);
        this.timeColumn.set('');
        this.timeCutoff.set(null);
        this.history.set(emptyHistory()); // a fresh graph invalidates prior undo/redo snapshots
        try {
            const g = await source.query(q);
            this.graph.set(g);
            this.resolvePendingPivot(g);
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

    // ── display options ──

    /** Pick (or with `null` clear) the stroke colour for a node kind. */
    setNodeColor(kind: string, color: string | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.nodeColors();
        this.nodeColors.set(color ? { ...rest, [kind]: color } : rest);
    }

    /** Pick (or with `null` clear) the stroke colour for a relationship kind. */
    setEdgeColor(kind: string, color: string | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.edgeColors();
        this.edgeColors.set(color ? { ...rest, [kind]: color } : rest);
    }

    /** Pick (or with `null` clear) the shape ("icon") for a node kind. */
    setNodeShape(kind: string, shape: string | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.nodeShapes();
        this.nodeShapes.set(shape ? { ...rest, [kind]: shape } : rest);
    }

    /** Pick (or with `null` clear) the line pattern for a relationship kind. */
    setEdgePattern(kind: string, pattern: EdgePattern | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.edgePatterns();
        this.edgePatterns.set(pattern ? { ...rest, [kind]: pattern } : rest);
    }

    /** Pick (or with `null` clear) the line width for a relationship kind. */
    setEdgeSize(kind: string, size: number | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.edgeSizes();
        this.edgeSizes.set(size != null ? { ...rest, [kind]: size } : rest);
    }

    /** Pick a graph layout (tree layouts are gated on {@link isTreeShaped} in the template). */
    setLayout(id: GraphLayoutId): void {
        this.recordHistory();
        this.layoutId.set(id);
    }

    // ── undo/redo (Phase G, presentation state only — not the query/graph itself) ──

    private readonly history = signal<HistoryStack<PresentationSnapshot>>(emptyHistory());
    readonly canUndo = computed(() => this.history().past.length > 0);
    readonly canRedo = computed(() => this.history().future.length > 0);

    private snapshotPresentation(): PresentationSnapshot {
        return {
            search: this.search(), kindFilter: this.kindFilter(),
            timeColumn: this.timeColumn(), timeCutoff: this.timeCutoff(),
            collapsedRoots: this.collapsedRoots(),
            nodeLabels: this.nodeLabels(), edgeLabels: this.edgeLabels(),
            nodeColors: this.nodeColors(), edgeColors: this.edgeColors(),
            nodeShapes: this.nodeShapes(), edgePatterns: this.edgePatterns(), edgeSizes: this.edgeSizes(),
            layoutId: this.layoutId(),
        };
    }

    private restorePresentation(s: PresentationSnapshot): void {
        this.search.set(s.search);
        this.kindFilter.set(s.kindFilter);
        this.timeColumn.set(s.timeColumn);
        this.timeCutoff.set(s.timeCutoff);
        this.collapsedRoots.set(s.collapsedRoots);
        this.nodeLabels.set(s.nodeLabels);
        this.edgeLabels.set(s.edgeLabels);
        this.nodeColors.set(s.nodeColors);
        this.edgeColors.set(s.edgeColors);
        this.nodeShapes.set(s.nodeShapes);
        this.edgePatterns.set(s.edgePatterns);
        this.edgeSizes.set(s.edgeSizes);
        this.layoutId.set(s.layoutId);
    }

    /** Call before a presentation-state mutation to make it undoable. */
    private recordHistory(): void {
        this.history.update((h) => pushHistory(h, this.snapshotPresentation()));
    }

    undoPresentation(): void {
        const result = undoHistory(this.history(), this.snapshotPresentation());
        if (!result) return;
        this.history.set(result.history);
        this.restorePresentation(result.state);
    }

    redoPresentation(): void {
        const result = redoHistory(this.history(), this.snapshotPresentation());
        if (!result) return;
        this.history.set(result.history);
        this.restorePresentation(result.state);
    }

    /** Ctrl/Cmd+Z undo, Ctrl/Cmd+Shift+Z (or Ctrl+Y) redo — ignored while typing in a form field. */
    onHistoryKeydown(e: KeyboardEvent): void {
        const target = e.target as HTMLElement | null;
        if (target && ['INPUT', 'TEXTAREA'].includes(target.tagName)) return;
        if (!(e.ctrlKey || e.metaKey)) return;
        if (e.key.toLowerCase() === 'z' && !e.shiftKey) { e.preventDefault(); this.undoPresentation(); }
        else if ((e.key.toLowerCase() === 'z' && e.shiftKey) || e.key.toLowerCase() === 'y') {
            e.preventDefault();
            this.redoPresentation();
        }
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
        if (this.collapsedRoots().includes(id)) return;
        this.recordHistory();
        this.collapsedRoots.set([...this.collapsedRoots(), id]);
    }

    expandBranch(id: string): void {
        this.recordHistory();
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
        const objectRef = node.data.objectRef;
        const source = this.graphSources.byId(this.sourceId());
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
                    objectRef,
                    pivotViews: objectRef ? ['map'] : undefined,
                    expandable: !!source?.expand,
                },
            })
            .afterClosed()
            .subscribe((action: ElementDetailResult) => {
                if (action === 'focus') this.focusNode(id);
                else if (action === 'collapse') this.collapseBranch(id);
                else if (action === 'expand') this.expandBranch(id);
                else if (action === 'expand-neighbors') this.expandNode(id, node.data.label);
                else if (action === 'open-record' && objectRef) {
                    this.router.navigate(['/' + (objectRef.type === 'CASE' ? 'cases' : 'incidents'), objectRef.id]);
                }
            });
    }

    /**
     * Phase E incremental expand: fetch `nodeLabel`'s one-hop neighborhood via the current source's
     * `expand()` and merge it into the loaded graph — filters/analysis state survives (unlike `run()`,
     * which replaces the graph and resets them).
     */
    async expandNode(id: string, nodeLabel: string): Promise<void> {
        const run = this.lastRun();
        const source = this.graphSources.byId(this.sourceId());
        if (!run || !source?.expand) return;
        try {
            const extra = await source.expand(id, nodeLabel, run.query);
            this.graph.update((g) => (g ? mergeGraphs([g, extra]) : extra));
        } catch (err) {
            this.toastr.error(apiErrorMessage(err, 'Could not fetch neighbors for this node.'));
        }
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

    // `search` deliberately isn't recorded per keystroke here (Phase G) — that would flood the undo
    // stack with one entry per character typed. `clearFilters` still records, since it's a single action.
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
        this.recordHistory();
        const all = this.nodeKinds();
        const current = this.kindFilter().length ? this.kindFilter() : all;
        const next = on ? [...new Set([...current, kind])] : current.filter((k) => k !== kind);
        this.kindFilter.set(next.length === all.length ? [] : next);
    }

    kindOn(kind: string): boolean {
        return !this.kindFilter().length || this.kindFilter().includes(kind);
    }

    /** Pick the timeline's `attrs` column (or '' to turn it off); resets the cutoff to the column's max. */
    setTimeColumn(col: string): void {
        this.recordHistory();
        this.timeColumn.set(col);
        this.timeCutoff.set(col ? (this.timeExtent()?.[1] ?? null) : null);
    }

    /** Move the timeline cutoff (epoch millis, from the slider). Not recorded per-drag (like search). */
    setTimeCutoff(cutoff: number): void {
        this.timeCutoff.set(cutoff);
    }

    /** Short date-time label for the timeline slider readout. */
    timeLabel(t: number): string {
        return new Date(t).toISOString().slice(0, 16).replace('T', ' ');
    }

    clearFilters(): void {
        this.recordHistory();
        this.kindFilter.set([]);
        this.search.set('');
        this.timeColumn.set('');
        this.timeCutoff.set(null);
        this.emphasis.set(null);
    }

    /** Emphasize a single node on the canvas (pivot resolution, table-row click, node-detail focus). */
    focusNode(id: string): void {
        this.emphasis.set({ nodeIds: [id], edgeIds: [] });
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

    /** Download the rendered graph as a standalone `link-analysis.svg` (Phase F). */
    exportSvg(): void {
        const svg = this.graphView?.exportSvg();
        if (svg) this.download(URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' })), 'link-analysis.svg');
        else this.toastr.warning('Nothing rendered to export yet.');
    }

    /** Download the displayed graph as generic `link-analysis.graphml` (Phase F, no vendor dialect). */
    exportGraphml(): void {
        const g = this.displayed();
        if (!g) return;
        this.download(URL.createObjectURL(new Blob([toGraphml(g)], { type: 'application/xml' })), 'link-analysis.graphml');
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
        const q = this.queryPanel?.buildQuery() ?? { error: 'The query form is not ready.' };
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
            await firstValueFrom(this.viewsService.save(view, { update: this.views().some((v) => v.id === view.id) }));
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
        this.queryPanel?.patchFormFromView(view);
        await this.execute(view.sourceId, view.query);
    }
}
