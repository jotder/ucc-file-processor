import {
    ChangeDetectionStrategy,
    Component,
    OnInit,
    ViewEncapsulation,
    computed,
    inject,
    signal,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FlowCombined, FlowGraph, FlowNode, FlowSummary, FlowsService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import {
    CATEGORY_ORDER,
    COMBINED_CATEGORY_ORDER,
    NodeTypeGroup,
    categoryColor,
    groupByCategory,
    nodeDisplayLabel,
    toCombinedG6Data,
    toFlowG6Data,
} from './flow-graph';

/** Which lens the Flows pane shows: each pipeline on its own, or all flows joined at their shared stores. */
export type FlowsViewMode = 'flow' | 'combined';

/**
 * Flows — the read-only pipeline-as-graph visualiser (doc §6, T31). Lists every registered pipeline
 * (lifted to a flow graph by the backend), renders the selected flow in the shared G6 host, shows a
 * node-type palette grouped by category, and a node inspector on click. Read-only: authoring / CRUD /
 * per-node dry-run land in a later phase.
 */
@Component({
    selector: 'app-flows',
    standalone: true,
    imports: [
        NgTemplateOutlet,
        MatButtonModule,
        MatButtonToggleModule,
        MatIconModule,
        MatTooltipModule,
        GraphViewComponent,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './flows.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class FlowsComponent implements OnInit {
    private api = inject(FlowsService);

    readonly flows = signal<FlowSummary[]>([]);
    readonly nodeTypeGroups = signal<NodeTypeGroup[]>([]);
    readonly selected = signal<string | null>(null);
    readonly graph = signal<FlowGraph | null>(null);
    readonly selectedNode = signal<FlowNode | null>(null);
    readonly loading = signal(false);
    readonly graphLoading = signal(false);
    readonly unavailable = signal(false);

    // ── combined topology (T24): all flows joined at their shared stores ──
    readonly mode = signal<FlowsViewMode>('flow');
    readonly combined = signal<FlowCombined | null>(null);
    readonly combinedLoading = signal(false);
    readonly combinedUnavailable = signal(false);

    /** The selected flow's graph mapped to G6 data for the shared renderer. */
    readonly g6Data = computed<G6GraphData | null>(() => {
        const g = this.graph();
        return g ? toFlowG6Data(g) : null;
    });

    /** The combined topology mapped to G6 data (flow nodes + synthetic store join nodes). */
    readonly combinedG6 = computed<G6GraphData | null>(() => {
        const c = this.combined();
        return c ? toCombinedG6Data(c) : null;
    });

    readonly nodeDisplayLabel = nodeDisplayLabel;
    readonly categoryColor = categoryColor;
    readonly combinedLegend = COMBINED_CATEGORY_ORDER;

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.unavailable.set(false);
        this.api.list().subscribe({
            next: (fs) => {
                this.flows.set(fs);
                this.loading.set(false);
                if (fs.length && !this.selected()) this.select(fs[0].name);
            },
            error: () => {
                this.loading.set(false);
                this.flows.set([]);
                this.unavailable.set(true);
            },
        });
        // The palette degrades independently — a failed catalog fetch must not blank the page.
        this.api.nodeTypes().subscribe({
            next: (ts) => this.nodeTypeGroups.set(groupByCategory(ts)),
            error: () => this.nodeTypeGroups.set([]),
        });
    }

    select(name: string): void {
        if (this.selected() === name && this.graph()) return;
        this.selected.set(name);
        this.selectedNode.set(null);
        this.graphLoading.set(true);
        this.api.graph(name).subscribe({
            next: (g) => {
                this.graph.set(g);
                this.graphLoading.set(false);
            },
            error: () => {
                this.graph.set(null);
                this.graphLoading.set(false);
            },
        });
    }

    onNodeClick(id: string): void {
        const pool = this.mode() === 'combined' ? this.combined()?.nodes : this.graph()?.nodes;
        this.selectedNode.set(pool?.find((n) => n.id === id) ?? null);
    }

    /** Switch lens; lazy-load the combined topology the first time it is shown. */
    setMode(m: FlowsViewMode): void {
        if (this.mode() === m) return;
        this.mode.set(m);
        this.selectedNode.set(null);
        if (m === 'combined' && !this.combined() && !this.combinedLoading()) this.loadCombined();
    }

    loadCombined(): void {
        this.combinedLoading.set(true);
        this.combinedUnavailable.set(false);
        this.api.combined().subscribe({
            next: (c) => {
                this.combined.set(c);
                this.combinedLoading.set(false);
            },
            error: () => {
                this.combined.set(null);
                this.combinedLoading.set(false);
                this.combinedUnavailable.set(true);
            },
        });
    }

    /** A category accent dot for the legend / palette (runtime colour from the token palette). */
    readonly legendCategories = CATEGORY_ORDER;
}
