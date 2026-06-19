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
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    AuthoredEdge,
    AuthoredFlow,
    AuthoredNode,
    FlowDryRunResult,
    FlowsService,
    FlowSummary,
    apiErrorMessage,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { FlowEditorGraphComponent } from './flow-editor-graph.component';
import {
    NodeTypeGroup,
    authoredToG6,
    categoryColor,
    categoryVisualKind,
    groupByCategory,
    typeCategoryMap,
} from './flow-graph';

/**
 * Flow editor (T32, build-side NiFi UX) — author/edit a `*_flow.toon` flow on an interactive G6 canvas:
 * drag node types from the palette, click two nodes to connect, edit node config in the inspector, dry-run a
 * sample, and Save (PUT). The {@link AuthoredFlow} signal is the logical truth; the canvas owns layout. Loaded
 * losslessly via {@code GET …/raw} so node config round-trips. Reached via the Flows pane's `editor` mode.
 */
@Component({
    selector: 'app-flow-editor',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        FlowEditorGraphComponent,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './flow-editor.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class FlowEditorComponent implements OnInit {
    private api = inject(FlowsService);
    private fb = inject(FormBuilder);
    private toast = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    @ViewChild(FlowEditorGraphComponent) private canvas?: FlowEditorGraphComponent;

    readonly flows = signal<FlowSummary[]>([]);
    readonly selectedId = signal<string | null>(null);
    readonly model = signal<AuthoredFlow | null>(null);
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
    readonly dryRunResult = signal<FlowDryRunResult | null>(null);
    readonly dryRunError = signal<string | null>(null);

    readonly creating = signal(false);
    readonly newName = this.fb.control('', { nonNullable: true, validators: [Validators.required] });
    readonly sampleText = this.fb.control('[\n  {}\n]', { nonNullable: true });

    /** Node inspector form (config is an array of key/value rows for scalar params). */
    readonly nodeForm = this.fb.group({
        name: this.fb.control(''),
        description: this.fb.control(''),
        use: this.fb.control(''),
        config: this.fb.array<ReturnType<FlowEditorComponent['configRow']>>([]),
    });

    readonly categoryColor = categoryColor;

    /** The selected flow's editable model mapped to G6 data — fed to the host only on a flow switch. */
    readonly g6Data = computed<G6GraphData | null>(() => {
        const m = this.model();
        return m ? authoredToG6(m, this.typeCat()) : null;
    });

    get configRows(): FormArray {
        return this.nodeForm.get('config') as FormArray;
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
            },
            error: () => this.paletteGroups.set([]),
        });
    }

    select(id: string): void {
        this.clearSelection();
        this.api.authoredRaw(id).subscribe({
            next: (flow) => {
                this.model.set(flow);
                this.selectedId.set(id); // drives the host rebuild (graphKey)
                this.dirty.set(false);
            },
            error: (err) => this.toast.error(apiErrorMessage(err, 'Could not load the flow')),
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
        const flow: AuthoredFlow = { name, active: false, nodes: [], edges: [] };
        this.api.createAuthored(flow).subscribe({
            next: () => {
                this.creating.set(false);
                this.flows.update((fs) => [...fs, { name, active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] }]);
                this.select(name);
            },
            error: (err) => this.onWriteError(err, 'Could not create the flow'),
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
                this.toast.success(`Saved flow '${id}'`);
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
            `Permanently delete the authored flow '${id}'?`,
            { title: 'Delete flow', confirmText: 'Delete' },
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
        if (node) this.loadNodeForm(node);
    }

    onEdgeSelected(id: string): void {
        this.selectedNode.set(null);
        this.connectFrom.set(null);
        this.selectedEdgeId.set(id);
    }

    onBackgroundClick(): void {
        this.clearSelection();
    }

    onDropAdd(e: { type: string; x: number; y: number }): void {
        const m = this.model();
        if (!m) return;
        const id = this.uniqueNodeId(e.type);
        const node: AuthoredNode = { id, type: e.type };
        this.model.set({ ...m, nodes: [...m.nodes, node] });
        this.canvas?.addNode(id, id, categoryVisualKind(this.typeCat().get(e.type) ?? 'TRANSFORM'), e.x, e.y);
        this.dirty.set(true);
        this.selectedNode.set(node);
        this.loadNodeForm(node);
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

    // ── inspector apply ──

    applyNode(): void {
        const node = this.selectedNode();
        const m = this.model();
        if (!node || !m) return;
        const v = this.nodeForm.getRawValue();
        const config: Record<string, unknown> = {};
        for (const row of v.config as { key: string; value: string }[]) {
            if (row.key && row.key.trim()) config[row.key.trim()] = row.value;
        }
        const updated: AuthoredNode = {
            id: node.id,
            type: node.type,
            name: v.name?.trim() || undefined,
            description: v.description?.trim() || undefined,
            use: v.use?.trim() || undefined,
            config: Object.keys(config).length ? config : undefined,
        };
        this.model.set({ ...m, nodes: m.nodes.map((n) => (n.id === node.id ? updated : n)) });
        this.selectedNode.set(updated);
        this.canvas?.updateNodeLabel(node.id, updated.name || updated.id);
        this.dirty.set(true);
    }

    addConfigRow(): void {
        this.configRows.push(this.configRow('', ''));
    }

    removeConfigRow(i: number): void {
        this.configRows.removeAt(i);
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

    // ── helpers ──

    private addEdge(from: string, to: string, rel: string): void {
        const m = this.model();
        if (!m) return;
        if (m.edges.some((e) => e.from === from && e.to === to && e.rel === rel)) return; // no dup
        const edge: AuthoredEdge = { from, rel, to };
        this.model.set({ ...m, edges: [...m.edges, edge] });
        this.canvas?.addEdge(`${from}->${to}:${rel}:${Date.now()}`, from, to, rel);
        this.dirty.set(true);
    }

    private removeNode(id: string): void {
        const m = this.model();
        if (!m) return;
        this.model.set({
            ...m,
            nodes: m.nodes.filter((n) => n.id !== id),
            edges: m.edges.filter((e) => e.from !== id && e.to !== id),
        });
        this.canvas?.removeElement(id);
        this.clearSelection();
        this.dirty.set(true);
    }

    private removeEdgeById(g6EdgeId: string): void {
        // the host edge id is "<from>-><to>:<rel>:<n>"; recover the endpoints to drop it from the model
        const m = this.model();
        if (!m) return;
        const match = /^(.*)->(.*):([^:]*):[^:]*$/.exec(g6EdgeId);
        if (match) {
            const [, from, to, rel] = match;
            this.model.set({ ...m, edges: m.edges.filter((e) => !(e.from === from && e.to === to && e.rel === rel)) });
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

    private loadNodeForm(node: AuthoredNode): void {
        this.configRows.clear();
        const cfg = node.config ?? {};
        for (const [key, value] of Object.entries(cfg)) {
            this.configRows.push(this.configRow(key, typeof value === 'string' ? value : JSON.stringify(value)));
        }
        this.nodeForm.patchValue({
            name: node.name ?? '',
            description: node.description ?? '',
            use: node.use ?? '',
        });
    }

    private configRow(key: string, value: string) {
        return this.fb.group({
            key: this.fb.control(key, { nonNullable: true }),
            value: this.fb.control(value, { nonNullable: true }),
        });
    }

    private uniqueNodeId(type: string): string {
        const base = type.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'node';
        const ids = new Set(this.model()?.nodes.map((n) => n.id));
        let i = 1;
        let id = `${base}_${i}`;
        while (ids.has(id)) id = `${base}_${++i}`;
        return id;
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
