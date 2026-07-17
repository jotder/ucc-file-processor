import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import type { ColDef } from 'ag-grid-community';
import { DecisionRulesService, JobsService, PipelinesService } from 'app/inspecto/api';
import { RequirementsService } from 'app/inspecto/requirement';
import { Component as ModelComponent, Part, deriveComponentGraph, refsForComponent } from 'app/inspecto/component-model';
import { G6GraphData } from 'app/inspecto/graph';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { ComponentsDataProvider } from './components-data-provider';
import { registerPlatformKinds } from './platform-kinds';

/** The component-registry kinds the reuse-graph loads (the backend `ComponentType`s). Pipelines are loaded
 *  separately (authored flows via {@link PipelinesService}) since they live in their own store, not `/components`.
 *  The saved investigation views joined with R1 (widget→view→dataset edges now derive). */
export const REGISTRY_KINDS = ['dataset', 'query', 'widget', 'dashboard', 'grammar', 'schema', 'transform', 'sink', 'rule', 'geo-map-view', 'link-analysis-view'];

/** The kinds a pipeline node may bind (mirrors `PIPELINE_KIND.allowedPartKinds`); a node's `use=<kind>/<id>`
 *  ref is turned into a part only for these, so source→connection refs don't clutter the graph. */
const PIPELINE_REF_KINDS = new Set(['grammar', 'schema', 'transform', 'sink']);

/** Editors that exist today, for the node-detail "Open" link; kinds without one (atomic registry kinds) get none. */
const EDITOR_PATH: Record<string, string> = {
    dataset: '/catalog/datasets',
    query: '/studio/queries',
    widget: '/studio/widgets',
    dashboard: '/studio/dashboards',
};

/**
 * **Registry / reuse-graph** (adoption-plan P3): a single lens over every component kind. Loads all registry
 * components via the {@link ComponentsDataProvider}, derives their composition ∪ reference graph
 * ({@link deriveComponentGraph}) and renders it through the **existing** `GraphViewComponent`, alongside a
 * read-only reference table. A node click reveals the component's detail (kind, references, an editor link).
 *
 * The relationship graph is **derived**, not a new store — references come from each composite's config via
 * the R1 ref derivation ({@link refsForComponent}: a widget's dataset + saved view, a dashboard's widget
 * tiles, a view's datasets, a pipeline's `use:` bindings). Registers the platform kinds on load.
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
    private flows = inject(PipelinesService);
    private jobs = inject(JobsService);
    private decisionRules = inject(DecisionRulesService);
    private requirements = inject(RequirementsService);

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
        const [compResults, pipelines, jobs, decisionRules, requirements] = await Promise.all([
            Promise.allSettled(REGISTRY_KINDS.map((k) => this.provider.list(k))),
            this.loadPipelines(),
            this.loadJobs(),
            this.loadDecisionRules(),
            this.loadRequirements(),
        ]);
        const comps: ModelComponent[] = [];
        for (const r of compResults) {
            if (r.status === 'fulfilled') {
                comps.push(...r.value.map((c) => ({ ...c, parts: refParts(c.kind, c.config as Record<string, unknown>) })));
            }
        }
        comps.push(...pipelines, ...jobs, ...decisionRules, ...requirements);
        this.components.set(comps);
        this.loading.set(false);
    }

    /** Load requirements as `requirement` components (own store, C1 follow-up) — a delivered requirement
     *  whose note is a pure `<kind>/<id>` ref list contributes its `delivered-by` edge(s) to the graph. */
    private async loadRequirements(): Promise<ModelComponent[]> {
        try {
            const reqs = await firstValueFrom(this.requirements.list());
            return reqs.map((r) => {
                const config = r as unknown as Record<string, unknown>;
                return { kind: 'requirement', id: r.id, name: r.title, config, parts: refParts('requirement', config) };
            });
        } catch {
            return [];
        }
    }

    /** Load decision rules as `decision-rule` components (own store, R5) — their `binds` (target) + `invokes`
     *  (platform-consequence target) edges join the graph. */
    private async loadDecisionRules(): Promise<ModelComponent[]> {
        try {
            const rules = await firstValueFrom(this.decisionRules.list());
            return rules.map((r) => {
                const config = r as unknown as Record<string, unknown>;
                return { kind: 'decision-rule', id: r.name, name: r.name, config, parts: refParts('decision-rule', config) };
            });
        } catch {
            return [];
        }
    }

    /** Load jobs as `job` components (own store, like pipelines) — their `triggers` edge joins the graph (R2). */
    private async loadJobs(): Promise<ModelComponent[]> {
        try {
            const jobs = await firstValueFrom(this.jobs.list());
            return jobs.map((j) => {
                const config = j as unknown as Record<string, unknown>;
                return { kind: 'job', id: j.name, name: j.name, config, parts: refParts('job', config) };
            });
        } catch {
            return [];
        }
    }

    /** Load authored flows as `pipeline` components, with parts derived from each node's `use=<kind>/<id>` ref. */
    private async loadPipelines(): Promise<ModelComponent[]> {
        try {
            const flows = await firstValueFrom(this.flows.authoredList());
            const loaded = await Promise.all(
                flows.map(async (f): Promise<ModelComponent | null> => {
                    try {
                        const flow = await firstValueFrom(this.flows.authoredRaw(f.name));
                        const config = flow as unknown as Record<string, unknown>; // carried opaquely; parts already derived
                        return { kind: 'pipeline', id: f.name, name: flow.name || f.name, config, parts: refParts('pipeline', config, PIPELINE_REF_KINDS) };
                    } catch {
                        return null;
                    }
                }),
            );
            return loaded.filter((p): p is ModelComponent => p !== null);
        } catch {
            return [];
        }
    }

    onNodeClick(id: string): void {
        this.selectedId.set(id);
    }

    /** The in-app editor route for a component, or null when its kind has no editor yet. */
    editorLink(c: ModelComponent): string[] | null {
        if (c.kind === 'pipeline') return ['/pipelines']; // the Pipelines editor page
        if (c.kind === 'job') return ['/jobs']; // the Jobs pane (dialog-based editing, no /:id route)
        if (c.kind === 'decision-rule') return ['/decision-rules']; // the Decision Rules pane (dialog-based editing)
        if (c.kind === 'requirement') return ['/requirements']; // the Requirements pane (dialog-based detail)
        const path = EDITOR_PATH[c.kind];
        return path ? [path, c.id] : null;
    }
}

/** Lift the R1 ref derivation onto reuse-graph {@link Part}s. `keep` is a display filter over target kinds
 *  (the pipeline view hides connection refs so source→connection edges don't clutter the graph). */
function refParts(kind: string, config: Record<string, unknown>, keep?: Set<string>): Part[] {
    return refsForComponent(kind, config)
        .filter((r) => !keep || keep.has(r.kind))
        .map((r, i) => ({ partId: r.via ?? `${r.rel}${i}`, ref: { kind: r.kind, id: r.id } }));
}

/** Split a `kind/id` node id (the id may itself contain `/`). */
function splitRef(nodeId: string): { kind: string; id: string } {
    const i = nodeId.indexOf('/');
    return i < 0 ? { kind: nodeId, id: nodeId } : { kind: nodeId.slice(0, i), id: nodeId.slice(i + 1) };
}

