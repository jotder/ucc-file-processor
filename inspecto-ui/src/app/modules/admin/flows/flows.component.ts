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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FlowCombined, FlowNode, FlowsService, IconMap, IconMapService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { FlowEditorComponent } from './flow-editor.component';
import {
    CATEGORY_ORDER,
    NodeTypeGroup,
    categoryColor,
    groupByCategory,
    nodeDisplayLabel,
    toCombinedG6Data,
} from './flow-graph';

/** Which lens the Pipelines pane shows: the (multi-pipeline) topology View, or the authoring Editor. */
export type FlowsViewMode = 'combined' | 'editor';

/**
 * Pipelines — the topology **View** (one or many pipelines, joined at their shared stores, rendered in the
 * shared G6 host with a node-type palette + a node inspector on click) and the authoring **Editor**. The
 * View is read-only; selecting a single pipeline in the multiselect just narrows the same topology.
 */
@Component({
    selector: 'app-flows',
    standalone: true,
    imports: [
        NgTemplateOutlet,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatMenuModule,
        MatSelectModule,
        MatTooltipModule,
        GraphViewComponent,
        InspectoEmptyStateComponent,
        FlowEditorComponent,
    ],
    templateUrl: './flows.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class FlowsComponent implements OnInit {
    private api = inject(FlowsService);
    private iconMapApi = inject(IconMapService);

    /** Configurable processor icons/colours (empty until loaded → mappers fall back to the per-kind glyph). */
    readonly iconMap = signal<IconMap>({});
    readonly nodeTypeGroups = signal<NodeTypeGroup[]>([]);
    readonly selectedNode = signal<FlowNode | null>(null);

    /** Which lens is shown: the topology View (combined) or the authoring Editor. */
    readonly mode = signal<FlowsViewMode>('combined');

    // ── topology View (T24): one or many pipelines joined at their shared stores ──
    readonly combined = signal<FlowCombined | null>(null);
    readonly combinedLoading = signal(false);
    readonly combinedUnavailable = signal(false);
    /** Which pipelines are shown (empty ⇒ all) + the multiselect's search box. */
    readonly combinedSelected = signal<string[]>([]);
    readonly combinedSearch = signal('');

    /** The topology mapped to G6 data, filtered to the chosen pipelines (empty selection ⇒ all). */
    readonly combinedG6 = computed<G6GraphData | null>(() => {
        const c = this.combined();
        if (!c) return null;
        const sel = this.combinedSelected();
        const active = sel.length ? new Set(sel) : new Set(c.flows.map((f) => f.name));
        const nodes = c.nodes.filter((n) => !n.flow || active.has(n.flow));
        const ids = new Set(nodes.map((n) => n.id));
        const edges = c.edges.filter((e) => ids.has(e.from) && ids.has(e.to));
        return toCombinedG6Data({ ...c, nodes, edges }, this.iconMap());
    });

    /** Pipeline names offered in the multiselect, filtered by its search box. */
    readonly combinedFlowOptions = computed<string[]>(() => {
        const q = this.combinedSearch().trim().toLowerCase();
        const names = (this.combined()?.flows ?? []).map((f) => f.name);
        return q ? names.filter((n) => n.toLowerCase().includes(q)) : names;
    });

    readonly nodeDisplayLabel = nodeDisplayLabel;
    readonly categoryColor = categoryColor;
    /** Category accent dots for the legend / palette (runtime colour from the token palette). */
    readonly legendCategories = CATEGORY_ORDER;

    ngOnInit(): void {
        this.load();
    }

    /** Load the palette, the icon map, and the combined topology (the View tab's data). */
    load(): void {
        // The palette degrades independently — a failed catalog fetch must not blank the page.
        this.api.nodeTypes().subscribe({
            next: (ts) => this.nodeTypeGroups.set(groupByCategory(ts)),
            error: () => this.nodeTypeGroups.set([]),
        });
        // Configurable icons degrade independently — failure just keeps the built-in per-kind glyphs.
        this.iconMapApi.get().subscribe({
            next: (m) => this.iconMap.set(m),
            error: () => this.iconMap.set({}),
        });
        this.loadCombined();
    }

    loadCombined(): void {
        this.combinedLoading.set(true);
        this.combinedUnavailable.set(false);
        this.api.combined().subscribe({
            next: (c) => {
                this.combined.set(c);
                if (!this.combinedSelected().length) this.combinedSelected.set(c.flows.map((f) => f.name));
                this.combinedLoading.set(false);
            },
            error: () => {
                this.combined.set(null);
                this.combinedLoading.set(false);
                this.combinedUnavailable.set(true);
            },
        });
    }

    /** Switch lens (topology View ↔ Editor). */
    setMode(m: FlowsViewMode): void {
        if (this.mode() === m) return;
        this.mode.set(m);
        this.selectedNode.set(null);
    }

    onNodeClick(id: string): void {
        this.selectedNode.set(this.combined()?.nodes.find((n) => n.id === id) ?? null);
    }

    onCombinedSearch(e: Event): void {
        this.combinedSearch.set((e.target as HTMLInputElement).value);
    }

    setCombinedSelected(names: string[]): void {
        this.combinedSelected.set(names);
    }
}
