import {
    ChangeDetectionStrategy,
    Component,
    OnInit,
    ViewChild,
    ViewEncapsulation,
    computed,
    inject,
    signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    AuthoredPipeline,
    AuthoredNode,
    ComponentsService,
    PipelineDryRunResult,
    PipelineRunResult,
    PipelinesService,
    PipelineSummary,
    IconMap,
    IconMapService,
    apiErrorMessage,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { PipelineEditorGraphComponent } from './pipeline-editor-graph.component';
import { PipelineInspectorComponent } from './pipeline-inspector.component';
import { PipelinePaletteComponent } from './pipeline-palette.component';
import { NodeConfigDialog, NodeConfigResult } from './node-config.dialog';
import { ParserConfigDialog } from './parser-config.dialog';
import { RunToHereDialog } from './run-to-here.dialog';
import {
    PipelineFinding,
    NodeStatus,
    NodeTypeGroup,
    TestOutcome,
    addEdgeToModel,
    addNodeToModel,
    applyNodePatchInModel,
    authoredToG6,
    bindKindFor,
    candidateRelsFor,
    categoryLabel,
    categoryVisualKind,
    computeNodeStatus,
    decodeEdgeId,
    encodeEdgeId,
    findingIcon,
    findingTint,
    groupByCategory,
    nodeConfigEntries,
    removeEdgeFromModel,
    removeNodeFromModel,
    setEdgeRelInModel,
    statusLabel,
    typeCategoryMap,
    uniqueNodeId,
    validatePipeline,
} from './pipeline-graph';

/**
 * Pipeline editor (T32, build-side NiFi UX) — author/edit a `*_flow.toon` pipeline on an interactive G6 canvas:
 * drag node types from the palette, click two nodes to connect, edit node config in the inspector, dry-run a
 * sample, and Save (PUT). The {@link AuthoredPipeline} signal is the logical truth; the canvas owns layout. Loaded
 * losslessly via {@code GET …/raw} so node config round-trips. Reached via the Pipelines pane's `editor` mode.
 *
 * The palette and the property drawer are their own presentational components
 * ({@link PipelinePaletteComponent}, {@link PipelineInspectorComponent}); every model mutation (add/remove
 * node or edge, re-label a relationship) is a pure reducer in `pipeline-graph.ts` — this container's job is
 * CRUD orchestration, canvas event wiring, and keeping the canvas in sync with the model (review:
 * `docs/superpower/reviews/pipeline-editor.md`).
 */
@Component({
    selector: 'app-pipeline-editor',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatMenuModule,
        MatSelectModule,
        MatSidenavModule,
        MatTooltipModule,
        PipelineEditorGraphComponent,
        PipelineInspectorComponent,
        PipelinePaletteComponent,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './pipeline-editor.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class PipelineEditorComponent implements OnInit {
    private api = inject(PipelinesService);
    private components = inject(ComponentsService);
    private iconMapApi = inject(IconMapService);
    private fb = inject(FormBuilder);
    private toast = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private dialog = inject(MatDialog);

    @ViewChild(PipelineEditorGraphComponent) private canvas?: PipelineEditorGraphComponent;

    readonly flows = signal<PipelineSummary[]>([]);
    /** Configurable processor icons/colours (empty until loaded → fall back to the per-kind glyph). */
    readonly iconMap = signal<IconMap>({});
    readonly selectedId = signal<string | null>(null);
    readonly model = signal<AuthoredPipeline | null>(null);
    readonly paletteGroups = signal<NodeTypeGroup[]>([]);
    private readonly typeCat = signal<Map<string, string>>(new Map());

    readonly selectedNode = signal<AuthoredNode | null>(null);
    readonly selectedEdgeId = signal<string | null>(null);
    /** Two-click edge creation: the first node clicked, awaiting a target. */
    readonly connectFrom = signal<string | null>(null);

    readonly dirty = signal(false);
    readonly saving = signal(false);
    readonly loading = signal(false);
    /** Set when a write returns 503 (no `-Dassist.write.root`) — the editor is read-only. */
    readonly unavailable = signal(false);

    readonly dryRunOpen = signal(false);
    readonly dryRunResult = signal<PipelineDryRunResult | null>(null);
    readonly dryRunError = signal<string | null>(null);

    // ── canvas status (Stage 2) + validation/activation (Stage 4) ──
    /** Known registry refs (`grammar/x`, `transform/y`, …) — drives dangling detection once loaded. */
    private readonly validRefs = signal<Set<string>>(new Set());
    private readonly refsLoaded = signal(false);
    /** Per-node test outcome from the last run-to-here (`tested` / `rejects`). */
    private readonly testedStatus = signal<Map<string, TestOutcome>>(new Map());
    /** node-type → emitted relationships, for the edge relationship picker. */
    private readonly typeEmits = signal<Map<string, string[]>>(new Map());
    readonly validateOpen = signal(false);
    readonly findings = signal<PipelineFinding[]>([]);
    readonly activating = signal(false);
    readonly statusLabel = statusLabel;
    readonly findingIcon = findingIcon;
    readonly findingTint = findingTint;

    readonly creating = signal(false);
    readonly newName = this.fb.control('', { nonNullable: true, validators: [Validators.required] });
    readonly sampleText = this.fb.control('[\n  {}\n]', { nonNullable: true });

    /** The property panel is collapsible (the editor body is just canvas + an optional inspector drawer). */
    readonly inspectorOpen = signal(true);
    /** Hover preview: the node under the cursor + its viewport position (null when not hovering). */
    readonly hoverTip = signal<{
        name: string;
        type: string;
        category: string;
        config: { k: string; v: string }[];
        x: number;
        y: number;
    } | null>(null);
    /** Whether a node or edge is selected — gates the toolbar Delete action (selection-scoped). */
    readonly hasSelection = computed(() => !!this.selectedNode() || !!this.selectedEdgeId());

    toggleInspector(): void {
        this.inspectorOpen.update((o) => !o);
    }

    /** The selected flow's editable model mapped to G6 data — fed to the host only on a flow switch. */
    readonly g6Data = computed<G6GraphData | null>(() => {
        const m = this.model();
        return m ? authoredToG6(m, this.typeCat(), (n) => this.statusOf(n), this.iconMap()) : null;
    });

    /** A node's authoring status — the canvas outline cue and the inspector chip. */
    statusOf(n: AuthoredNode): NodeStatus {
        return computeNodeStatus(n, this.typeCategory(n.type), this.validRefs(), this.testedStatus(), this.refsLoaded());
    }

    /** Push every node's current status to the canvas (after the registry refs or test outcomes change). */
    private refreshAllStatuses(): void {
        for (const n of this.model()?.nodes ?? []) this.canvas?.setNodeStatus(n.id, this.statusOf(n));
    }

    /** The palette category for a node type (drives the inspector's category label + colour). */
    typeCategory(type: string): string {
        return this.typeCat().get(type) ?? '';
    }

    /** Relationships the selected edge may carry — the inspector's picker options. */
    candidateRels(): string[] {
        const id = this.selectedEdgeId();
        return id ? candidateRelsFor(this.model(), id, this.typeEmits()) : [];
    }

    /** The relationship the selected edge currently carries. */
    selectedEdgeRel(): string | null {
        const id = this.selectedEdgeId();
        return id ? (decodeEdgeId(id)?.rel ?? null) : null;
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.authoredList().subscribe({
            next: (fs) => {
                this.flows.set(fs);
                this.loading.set(false);
                if (fs.length && !this.selectedId()) this.select(fs[0].name);
            },
            error: () => {
                this.flows.set([]);
                this.loading.set(false);
            },
        });
        this.api.nodeTypes().subscribe({
            next: (ts) => {
                this.paletteGroups.set(groupByCategory(ts));
                this.typeCat.set(typeCategoryMap(ts));
                this.typeEmits.set(new Map(ts.map((t) => [t.type, t.emits])));
            },
            error: () => this.paletteGroups.set([]),
        });
        this.loadComponentRefs();
        this.iconMapApi.get().subscribe({
            next: (m) => this.iconMap.set(m),
            error: () => this.iconMap.set({}),
        });
    }

    /** Load existing registry refs (grammar/transform/sink) so the canvas can flag dangling `use` bindings. */
    private loadComponentRefs(): void {
        const refs = new Set<string>();
        const kinds = ['grammar', 'transform', 'sink'] as const;
        let pending = kinds.length;
        const done = () => {
            if (--pending > 0) return;
            this.validRefs.set(refs);
            this.refsLoaded.set(true);
            this.refreshAllStatuses();
        };
        for (const k of kinds) {
            this.components.list(k).subscribe({
                next: (list) => {
                    for (const c of list) refs.add(c.ref);
                    done();
                },
                error: () => done(),
            });
        }
    }

    select(id: string): void {
        this.clearSelection();
        this.api.authoredRaw(id).subscribe({
            next: (flow) => {
                this.model.set(flow);
                this.selectedId.set(id); // drives the host rebuild (graphKey)
                this.dirty.set(false);
            },
            error: (err) => this.toast.error(apiErrorMessage(err, 'Could not load the pipeline')),
        });
    }

    // ── create / save / delete ──

    startNew(): void {
        this.creating.set(true);
        this.newName.reset('');
    }

    createFlow(): void {
        if (this.newName.invalid) {
            this.newName.markAsTouched();
            return;
        }
        const name = this.newName.value.trim();
        const flow: AuthoredPipeline = { name, active: false, nodes: [], edges: [] };
        this.api.createAuthored(flow).subscribe({
            next: () => {
                this.creating.set(false);
                this.flows.update((fs) => [...fs, { name, active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] }]);
                this.select(name);
            },
            error: (err) => this.onWriteError(err, 'Could not create the pipeline'),
        });
    }

    save(): void {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
        this.saving.set(true);
        this.api.replaceAuthored(id, m).subscribe({
            next: () => {
                this.saving.set(false);
                this.dirty.set(false);
                this.toast.success(`Saved pipeline '${id}'`);
            },
            error: (err) => {
                this.saving.set(false);
                this.onWriteError(err, 'Save failed');
            },
        });
    }

    async deleteFlow(): Promise<void> {
        const id = this.selectedId();
        if (!id) return;
        const ok = await this.confirm.confirmDestructive(
            `Permanently delete the authored pipeline '${id}'?`,
            { title: 'Delete pipeline', confirmText: 'Delete' },
        );
        if (!ok) return;
        this.api.deleteAuthored(id).subscribe({
            next: () => {
                this.flows.update((fs) => fs.filter((f) => f.name !== id));
                this.model.set(null);
                this.selectedId.set(null);
                this.clearSelection();
                const next = this.flows()[0];
                if (next) this.select(next.name);
            },
            error: (err) => this.onWriteError(err, 'Delete failed'),
        });
    }

    // ── canvas events ──

    onNodeSelected(id: string): void {
        const from = this.connectFrom();
        if (from && from !== id) {
            this.addEdge(from, id, 'data');
            this.connectFrom.set(null);
            return;
        }
        const node = this.model()?.nodes.find((n) => n.id === id) ?? null;
        this.selectedEdgeId.set(null);
        this.selectedNode.set(node);
        if (node) this.inspectorOpen.set(true); // reveal the property panel on selection
    }

    /** Double-click a node (or the inspector's Configure button) → open the per-processor config popup. */
    onNodeOpen(id: string): void {
        if (this.connectFrom()) return; // mid-connection: a double-click shouldn't pop the editor
        const node = this.model()?.nodes.find((n) => n.id === id);
        if (node) this.openNodeConfig(node);
    }

    openNodeConfig(node: AuthoredNode): void {
        const category = this.typeCategory(node.type);
        // Parsers get the rich multi-pane parser editor; every other category uses the generic config popup.
        const ref =
            category === 'PARSE'
                ? this.dialog.open(ParserConfigDialog, {
                      width: '1100px',
                      maxWidth: '95vw',
                      autoFocus: false,
                      data: { node, typeLabel: node.type, categoryLabel: categoryLabel(category) },
                  })
                : this.dialog.open(NodeConfigDialog, {
                      width: '520px',
                      autoFocus: false,
                      data: {
                          node,
                          typeLabel: node.type,
                          categoryLabel: categoryLabel(category),
                          bindKind: bindKindFor(category),
                      },
                  });
        ref.afterClosed().subscribe((res?: NodeConfigResult) => {
            if (res?.node) this.applyNodePatch(res.node);
        });
    }

    /** Open the run-to-here loop for a node: pick inbox files → run the subgraph up to it → see Parquet. */
    openRunToHere(node: AuthoredNode): void {
        const flowId = this.selectedId();
        const m = this.model();
        if (!flowId || !m) return;
        const src = m.nodes.find((n) => this.typeCategory(n.type) === 'SOURCE' && n.use?.startsWith('connections/'));
        const connectionId = src?.use ? src.use.slice('connections/'.length) : null;
        const ref = this.dialog.open(RunToHereDialog, {
            width: '760px',
            autoFocus: false,
            data: { flowId, node, connectionId },
        });
        ref.afterClosed().subscribe((r?: PipelineRunResult) => {
            if (r) this.applyRunOutcomes(r);
        });
    }

    /** Mark the run's nodes tested (canvas ✓), or `rejects` (✕) for any with unmatched rows. */
    private applyRunOutcomes(r: PipelineRunResult): void {
        const inRun = new Set(r.relations.map((rel) => rel.node));
        const rejected = new Set(
            r.relations.filter((rel) => rel.rel === 'unmatched' && rel.rowCount > 0).map((rel) => rel.node),
        );
        const next = new Map(this.testedStatus());
        for (const id of inRun) next.set(id, rejected.has(id) ? 'rejects' : 'tested');
        this.testedStatus.set(next);
        for (const id of inRun) {
            const node = this.model()?.nodes.find((n) => n.id === id);
            if (node) this.canvas?.setNodeStatus(id, this.statusOf(node));
        }
    }

    onEdgeSelected(id: string): void {
        this.selectedNode.set(null);
        this.connectFrom.set(null);
        this.selectedEdgeId.set(id);
        this.inspectorOpen.set(true);
    }

    onBackgroundClick(): void {
        this.clearSelection();
    }

    /** Drag-to-draw: G6 already drew the edge, so only record it in the model (default `data` relationship). */
    onEdgeCreated(e: { source: string; target: string }): void {
        if (e.source === e.target) return;
        this.addEdge(e.source, e.target, 'data', { skipCanvas: true });
    }

    /** Hover preview: resolve the hovered node from the live model into a tooltip (or clear on leave). */
    onNodeHover(h: { id: string; x: number; y: number } | null): void {
        if (!h) {
            this.hoverTip.set(null);
            return;
        }
        const n = this.model()?.nodes.find((x) => x.id === h.id);
        if (!n) {
            this.hoverTip.set(null);
            return;
        }
        this.hoverTip.set({
            name: n.name || n.id,
            type: n.type,
            category: categoryLabel(this.typeCat().get(n.type) ?? ''),
            config: nodeConfigEntries(n).slice(0, 5),
            x: h.x,
            y: h.y,
        });
    }

    /** Palette drag-drop: place the new node where it was dropped. */
    onDropAdd(e: { type: string; x: number; y: number }): void {
        if (!this.model()) return;
        const node = this.insertNode(e.type);
        this.canvas?.addNode(node.id, node.id, this.visualKind(e.type), e.x, e.y);
        this.selectNewNode(node);
    }

    /** Palette click / keyboard (Enter): add the node at the canvas centre — the no-mouse path to add. */
    addFromPalette(type: string): void {
        if (!this.model()) return;
        const node = this.insertNode(type);
        this.canvas?.addNodeAtCenter(node.id, node.id, this.visualKind(type));
        this.selectNewNode(node);
    }

    private visualKind(type: string): ReturnType<typeof categoryVisualKind> {
        return categoryVisualKind(this.typeCat().get(type) ?? 'TRANSFORM');
    }

    private insertNode(type: string): AuthoredNode {
        const id = uniqueNodeId(this.model(), type);
        const node: AuthoredNode = { id, type };
        this.model.update((m) => (m ? addNodeToModel(m, node) : m));
        return node;
    }

    private selectNewNode(node: AuthoredNode): void {
        this.canvas?.setNodeStatus(node.id, this.statusOf(node)); // a new node starts unconfigured/configured
        this.dirty.set(true);
        this.selectedNode.set(node);
        this.inspectorOpen.set(true);
    }

    onDeleteKey(): void {
        const edgeId = this.selectedEdgeId();
        const node = this.selectedNode();
        if (edgeId) {
            this.removeEdgeById(edgeId);
        } else if (node) {
            this.removeNode(node.id);
        }
    }

    /** Arm two-click edge creation from the inspector's selected node. */
    armConnect(): void {
        const n = this.selectedNode();
        if (n) this.connectFrom.set(n.id);
    }

    // ── node config apply (from the popup) ──

    /** Replace a node in the model with the popup's edited version, refreshing its canvas label + status. */
    private applyNodePatch(updated: AuthoredNode): void {
        const m = this.model();
        if (!m) return;
        this.model.set(applyNodePatchInModel(m, updated));
        if (this.selectedNode()?.id === updated.id) this.selectedNode.set(updated);
        this.canvas?.updateNodeLabel(updated.id, updated.name || updated.id);
        // A freshly chosen/created ref is valid by construction; editing config invalidates a prior test.
        if (updated.use) this.validRefs.update((s) => new Set(s).add(updated.use!));
        this.clearTested(updated.id);
        this.canvas?.setNodeStatus(updated.id, this.statusOf(updated));
        this.dirty.set(true);
    }

    private clearTested(id: string): void {
        if (!this.testedStatus().has(id)) return;
        this.testedStatus.update((m) => {
            const next = new Map(m);
            next.delete(id);
            return next;
        });
    }

    // ── dry-run ──

    runDryRun(): void {
        const id = this.selectedId();
        if (!id) return;
        let rows: Record<string, unknown>[];
        try {
            const parsed = JSON.parse(this.sampleText.value);
            rows = Array.isArray(parsed) ? parsed : [parsed];
        } catch {
            this.dryRunError.set('Sample must be valid JSON (an array of row objects)');
            this.dryRunResult.set(null);
            return;
        }
        this.dryRunError.set(null);
        this.api.dryRunAuthored(id, rows).subscribe({
            next: (r) => this.dryRunResult.set(r),
            error: (err) => {
                this.dryRunResult.set(null);
                this.dryRunError.set(apiErrorMessage(err, 'Dry-run failed'));
            },
        });
    }

    toggleDryRun(): void {
        this.dryRunOpen.update((o) => !o);
    }

    // ── edge relationship (Stage 2) ──

    /** Re-label the selected edge with a different relationship (the canvas edge id encodes the rel). */
    setEdgeRel(rel: string): void {
        const id = this.selectedEdgeId();
        const m = this.model();
        const p = id ? decodeEdgeId(id) : null;
        if (!id || !m || !p) return;
        const next = setEdgeRelInModel(m, p.from, p.to, p.rel, rel);
        if (!next) return; // unchanged or would collide with an existing edge
        this.model.set(next);
        this.canvas?.removeElement(id);
        const newId = encodeEdgeId(p.from, p.to, rel);
        this.canvas?.addEdge(newId, p.from, p.to, rel);
        this.selectedEdgeId.set(newId);
        this.dirty.set(true);
    }

    // ── validate & activate (Stage 4) ──

    /** Whether the selected flow is currently active. */
    isActive(): boolean {
        return this.model()?.active ?? false;
    }

    toggleValidate(): void {
        if (!this.validateOpen()) this.validate();
        else this.validateOpen.set(false);
    }

    /** Walk the flow for activation-blocking issues; opens the findings panel. */
    validate(): PipelineFinding[] {
        const m = this.model();
        const f = m ? validatePipeline(m, this.typeCat(), this.validRefs(), this.testedStatus()) : [];
        this.findings.set(f);
        this.validateOpen.set(true);
        return f;
    }

    /** Click a finding to select its node on the canvas. */
    selectFinding(nodeId?: string): void {
        if (nodeId) this.onNodeSelected(nodeId);
    }

    activate(): void {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
        if (this.validate().some((f) => f.severity === 'error')) {
            this.toast.error('Fix the errors below before activating.');
            return;
        }
        this.setActive(id, { ...m, active: true }, `Activated '${id}'`);
    }

    deactivate(): void {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
        this.setActive(id, { ...m, active: false }, `Deactivated '${id}'`);
    }

    private setActive(id: string, updated: AuthoredPipeline, ok: string): void {
        this.activating.set(true);
        this.api.replaceAuthored(id, updated).subscribe({
            next: () => {
                this.activating.set(false);
                this.model.set(updated);
                this.dirty.set(false);
                this.toast.success(ok);
            },
            error: (err) => {
                this.activating.set(false);
                this.onWriteError(err, 'Update failed');
            },
        });
    }

    // ── helpers ──

    /** Append `(from, to, rel)` to the model, syncing the canvas unless it already drew the edge itself. */
    private addEdge(from: string, to: string, rel: string, opts: { skipCanvas?: boolean } = {}): void {
        const m = this.model();
        if (!m) return;
        const next = addEdgeToModel(m, from, to, rel);
        if (!next) return; // duplicate — no-op
        this.model.set(next);
        if (!opts.skipCanvas) this.canvas?.addEdge(encodeEdgeId(from, to, rel), from, to, rel);
        this.dirty.set(true);
    }

    private removeNode(id: string): void {
        const m = this.model();
        if (!m) return;
        this.model.set(removeNodeFromModel(m, id));
        this.canvas?.removeElement(id);
        this.clearSelection();
        this.dirty.set(true);
    }

    private removeEdgeById(g6EdgeId: string): void {
        const m = this.model();
        const p = decodeEdgeId(g6EdgeId);
        if (m && p) {
            this.model.set(removeEdgeFromModel(m, p.from, p.to, p.rel));
            this.dirty.set(true);
        }
        this.canvas?.removeElement(g6EdgeId);
        this.selectedEdgeId.set(null);
    }

    private clearSelection(): void {
        this.selectedNode.set(null);
        this.selectedEdgeId.set(null);
        this.connectFrom.set(null);
    }

    private onWriteError(err: unknown, fallback: string): void {
        if ((err as { status?: number })?.status === 503) {
            this.unavailable.set(true);
            this.toast.error('Editing is read-only — the server has no write root (-Dassist.write.root).');
            return;
        }
        this.toast.error(apiErrorMessage(err, fallback));
    }
}
