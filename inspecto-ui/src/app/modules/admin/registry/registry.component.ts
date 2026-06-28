import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import type { ColDef } from 'ag-grid-community';
import { Component as ModelComponent, Part, deriveComponentGraph } from 'app/inspecto/component-model';
import { G6GraphData } from 'app/inspecto/graph';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { ComponentsDataProvider } from './components-data-provider';
import { registerPlatformKinds } from './platform-kinds';

/** The component-registry kinds the reuse-graph loads (the backend `ComponentType`s). Pipelines live in a
 *  separate store (authored flows via FlowsService) and integrate in a later pass. */
const REGISTRY_KINDS = ['dataset', 'chart', 'dashboard', 'grammar', 'schema', 'transform', 'sink', 'rule'];

/** Editors that exist today, for the node-detail "Open" link; kinds without one (atomic registry kinds) get none. */
const EDITOR_PATH: Record<string, string> = {
    dataset: '/studio/datasets',
    chart: '/studio/charts',
    dashboard: '/studio/dashboards',
};

/**
 * **Registry / reuse-graph** (adoption-plan P3): a single lens over every component kind. Loads all registry
 * components via the {@link ComponentsDataProvider}, derives their composition ∪ reference graph
 * ({@link deriveComponentGraph}) and renders it through the **existing** `GraphViewComponent`, alongside a
 * read-only reference table. A node click reveals the component's detail (kind, references, an editor link).
 *
 * The relationship graph is **derived**, not a new store — references come from each composite's config via
 * {@link partsFor} (a chart's dataset, a dashboard's chart tiles). Registers the platform kinds on load.
 */
@Component({
    selector: 'app-registry',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        RouterLink,
        DataTableComponent,
        InspectoEmptyStateComponent,
        GraphViewComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './registry.component.html',
})
export class RegistryComponent implements OnInit {
    private provider = inject(ComponentsDataProvider);

    readonly components = signal<ModelComponent[]>([]);
    readonly loading = signal(false);
    readonly selectedId = signal<string | null>(null);

    readonly graph = computed<G6GraphData>(() => deriveComponentGraph({ components: this.components() }));

    /** One row per reference edge (the "view over derived edges"). */
    readonly refRows = computed<Record<string, unknown>[]>(() =>
        this.graph().edges.map((e) => {
            const from = splitRef(e.source);
            const to = splitRef(e.target);
            return { component: to.id, kind: to.kind, usedBy: from.id, usedByKind: from.kind, rel: e.data.kind };
        }),
    );

    readonly refColumns: ColDef[] = [
        { field: 'component', headerName: 'Component' },
        { field: 'kind', headerName: 'Kind' },
        { field: 'usedBy', headerName: 'Used by' },
        { field: 'rel', headerName: 'Relationship' },
    ];

    /** The component behind the selected node (undefined for a dangling-ref ghost node). */
    readonly selected = computed<ModelComponent | undefined>(() => {
        const id = this.selectedId();
        return id ? this.components().find((c) => `${c.kind}/${c.id}` === id) : undefined;
    });

    /** The selected component's outgoing references (its parts), for the detail panel. */
    readonly selectedRefs = computed<Part[]>(() => this.selected()?.parts ?? []);

    ngOnInit(): void {
        registerPlatformKinds();
        this.load();
    }

    async load(): Promise<void> {
        this.loading.set(true);
        this.selectedId.set(null);
        const settled = await Promise.allSettled(REGISTRY_KINDS.map((k) => this.provider.list(k)));
        const comps: ModelComponent[] = [];
        for (const r of settled) {
            if (r.status === 'fulfilled') comps.push(...r.value.map((c) => ({ ...c, parts: partsFor(c) })));
        }
        this.components.set(comps);
        this.loading.set(false);
    }

    onNodeClick(id: string): void {
        this.selectedId.set(id);
    }

    /** The in-app editor route for a component, or null when its kind has no editor yet. */
    editorLink(c: ModelComponent): string[] | null {
        const path = EDITOR_PATH[c.kind];
        return path ? [path, c.id] : null;
    }
}

/** Split a `kind/id` node id (the id may itself contain `/`). */
function splitRef(nodeId: string): { kind: string; id: string } {
    const i = nodeId.indexOf('/');
    return i < 0 ? { kind: nodeId, id: nodeId } : { kind: nodeId.slice(0, i), id: nodeId.slice(i + 1) };
}

/**
 * Derive a component's reference parts from its (known) config — the references the reuse-graph draws edges
 * for. Reads the stable config shapes of the composite kinds (a chart → its dataset; a dashboard → its chart
 * tiles); atomic kinds have none. A `deriveParts` ComponentKind seam could formalize this later.
 */
function partsFor(c: ModelComponent): Part[] {
    if (c.kind === 'chart') {
        const datasetId = (c.config as { datasetId?: string }).datasetId;
        return datasetId ? [{ partId: 'dataset', ref: { kind: 'dataset', id: datasetId } }] : [];
    }
    if (c.kind === 'dashboard') {
        const tiles = (c.config as { tiles?: { chartId: string }[] }).tiles ?? [];
        return tiles.filter((t) => t.chartId).map((t, i) => ({ partId: `tile${i}`, ref: { kind: 'chart', id: t.chartId } }));
    }
    return [];
}
