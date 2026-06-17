import {
    ChangeDetectionStrategy,
    Component,
    OnInit,
    ViewEncapsulation,
    computed,
    inject,
    signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FlowGraph, FlowNode, FlowSummary, FlowsService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import {
    CATEGORY_ORDER,
    NodeTypeGroup,
    categoryColor,
    groupByCategory,
    nodeDisplayLabel,
    toFlowG6Data,
} from './flow-graph';

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
        MatButtonModule,
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

    /** The selected flow's graph mapped to G6 data for the shared renderer. */
    readonly g6Data = computed<G6GraphData | null>(() => {
        const g = this.graph();
        return g ? toFlowG6Data(g) : null;
    });

    readonly nodeDisplayLabel = nodeDisplayLabel;
    readonly categoryColor = categoryColor;

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
        const g = this.graph();
        this.selectedNode.set(g ? (g.nodes.find((n) => n.id === id) ?? null) : null);
    }

    /** A category accent dot for the legend / palette (runtime colour from the token palette). */
    readonly legendCategories = CATEGORY_ORDER;
}
