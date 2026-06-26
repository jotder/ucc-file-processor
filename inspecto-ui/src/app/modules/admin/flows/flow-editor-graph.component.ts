import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    ElementRef,
    EventEmitter,
    Input,
    OnChanges,
    OnDestroy,
    Output,
    SimpleChanges,
    ViewChild,
    inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { GammaConfigService } from '@gamma/services/config';
import { CanvasEvent, Graph, GraphData, NodeData, NodeEvent } from '@antv/g6';
import { G6GraphData, nodeColor, nodeIcon } from 'app/modules/admin/catalog/catalog-graph';
import { NodeKind } from 'app/inspecto/api';
import { canvasTheme, nodeStatusStroke } from 'app/inspecto/theme/chart-tokens';
import { NodeStatus, statusGlyph } from './flow-graph';

/**
 * Interactive AntV G6 host for the flow editor. Unlike the read-only {@link GraphViewComponent} (which
 * `destroy()`s + rebuilds on every data change), this host keeps a **persistent** graph and mutates it in
 * place ({@link addNode}/{@link addEdge}/{@link removeElement}/{@link updateNodeLabel}) so user-arranged
 * positions survive edits. It only rebuilds when {@link graphKey} (the selected flow id) changes or the
 * colour scheme flips. Authored flows store no coordinates, so node moves are purely visual (not emitted).
 *
 * <p>Gestures: plain **drag moves** a node (`drag-element`); **Shift+drag** from one node to another draws
 * an edge ({@link edgeCreated} via `create-edge`) — both share the drag gesture, so the Shift modifier
 * disambiguates them. Edge creation is also available **two-click** (driven by the parent via
 * {@link nodeSelected}). Double-click opens a node's config ({@link nodeOpen}). New nodes arrive by HTML5
 * drag-drop from the palette ({@link dropAdd}); Delete/Backspace removes the selection ({@link deleteKey}).
 */
@Component({
    selector: 'app-flow-editor-graph',
    standalone: true,
    template: `<div
        #host
        class="h-full w-full focus:outline-none"
        tabindex="0"
        role="application"
        aria-label="Flow editor canvas — drag a node type from the palette, drag node-to-node to connect, double-click a node to configure, Delete to remove"
        (dragover)="onDragOver($event)"
        (drop)="onDrop($event)"
        (keydown)="onKeydown($event)"
    ></div>`,
    host: { class: 'block h-160 w-full' },
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowEditorGraphComponent implements AfterViewInit, OnChanges, OnDestroy {
    /** Initial graph data; consumed on (re)build only — in-place edits go through the mutation methods. */
    @Input() data: G6GraphData | null = null;
    /** Rebuild the canvas only when this changes (the selected flow id). Never rebuild on in-place edits. */
    @Input() graphKey: string | null = null;

    @Output() nodeSelected = new EventEmitter<string>();
    /** Double-click a node to open its configuration popup (NiFi "Configure"). */
    @Output() nodeOpen = new EventEmitter<string>();
    @Output() edgeSelected = new EventEmitter<string>();
    @Output() backgroundClick = new EventEmitter<void>();
    @Output() dropAdd = new EventEmitter<{ type: string; x: number; y: number }>();
    @Output() deleteKey = new EventEmitter<void>();
    /** Drag-to-draw: a new edge was dragged from one node to another (source→target). */
    @Output() edgeCreated = new EventEmitter<{ source: string; target: string }>();
    /** Hover preview: node id + viewport coords on enter, or null on leave. */
    @Output() nodeHover = new EventEmitter<{ id: string; x: number; y: number } | null>();

    @ViewChild('host') private hostEl!: ElementRef<HTMLDivElement>;
    private graph: Graph | null = null;
    private dark = false;
    private ready = false;
    private destroyRef = inject(DestroyRef);

    constructor() {
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((config) => {
                const dark =
                    config?.scheme === 'dark' ||
                    (config?.scheme === 'auto' &&
                        window.matchMedia('(prefers-color-scheme: dark)').matches);
                if (dark !== this.dark) {
                    this.dark = dark;
                    if (this.ready) this.rebuild();
                }
            });
    }

    ngAfterViewInit(): void {
        this.ready = true;
        this.rebuild();
    }

    ngOnChanges(changes: SimpleChanges): void {
        // Rebuild only on a flow switch — in-place edits must not re-run layout (it discards positions).
        if (this.ready && changes['graphKey']) this.rebuild();
    }

    ngOnDestroy(): void {
        this.graph?.destroy();
    }

    // ── public mutation API (parent keeps the logical model; the canvas owns positions) ──

    addNode(id: string, label: string, kind: NodeKind, x?: number, y?: number): void {
        if (!this.graph) return;
        const node: Record<string, unknown> = { id, data: { label, kind } };
        if (x != null && y != null) node['style'] = { x, y };
        this.graph.addNodeData([node as unknown as NodeData]);
        this.graph.draw();
    }

    /** Add a node at the viewport centre — the keyboard/click path to add (no drag coordinates). */
    addNodeAtCenter(id: string, label: string, kind: NodeKind): void {
        if (!this.graph) return;
        const rect = this.hostEl.nativeElement.getBoundingClientRect();
        const [x, y] = this.toGraphPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
        this.addNode(id, label, kind, x, y);
    }

    addEdge(id: string, source: string, target: string, kind: string): void {
        if (!this.graph) return;
        this.graph.addEdgeData([{ id, source, target, data: { kind } }]);
        this.graph.draw();
    }

    removeElement(id: string): void {
        if (!this.graph) return;
        try {
            this.graph.removeNodeData([id]);
        } catch {
            /* not a node — try edge */
        }
        try {
            this.graph.removeEdgeData([id]);
        } catch {
            /* not an edge */
        }
        this.graph.draw();
    }

    updateNodeLabel(id: string, label: string): void {
        if (!this.graph) return;
        this.graph.updateNodeData([{ id, data: { label } }]);
        this.graph.draw();
    }

    /** Repaint a node's authoring status (outline colour + dash + label glyph). Merges into existing data. */
    setNodeStatus(id: string, status: NodeStatus): void {
        if (!this.graph) return;
        this.graph.updateNodeData([{ id, data: { status } }]);
        this.graph.draw();
    }

    // ── HTML5 drag-drop + keyboard ──

    onDragOver(e: DragEvent): void {
        e.preventDefault();
        if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
    }

    onDrop(e: DragEvent): void {
        e.preventDefault();
        const type = e.dataTransfer?.getData('text/flow-node-type');
        if (!type) return;
        const [x, y] = this.toGraphPoint(e.clientX, e.clientY);
        this.dropAdd.emit({ type, x, y });
    }

    onKeydown(e: KeyboardEvent): void {
        if (e.key === 'Delete' || e.key === 'Backspace') {
            e.preventDefault();
            this.deleteKey.emit();
        }
    }

    /** Client → graph coordinates for placing a dropped node (best-effort; falls back to the host offset). */
    private toGraphPoint(clientX: number, clientY: number): [number, number] {
        const g = this.graph as unknown as {
            getCanvasByClient?: (p: [number, number]) => { x: number; y: number };
        };
        try {
            const p = g.getCanvasByClient?.([clientX, clientY]);
            if (p && Number.isFinite(p.x) && Number.isFinite(p.y)) return [p.x, p.y];
        } catch {
            /* fall through to the host-relative offset */
        }
        const rect = this.hostEl.nativeElement.getBoundingClientRect();
        return [clientX - rect.left, clientY - rect.top];
    }

    private rebuild(): void {
        this.graph?.destroy();
        this.graph = null;
        const { fg, surface: nodeFill, edge } = canvasTheme(this.dark);
        const kindOf = (d: NodeData): NodeKind => (d.data as { kind: NodeKind }).kind;
        const statusOf = (d: NodeData): NodeStatus => (d.data as { status?: NodeStatus }).status ?? 'configured';
        const iconOf = (d: NodeData): string => (d.data as { iconSrc?: string }).iconSrc ?? nodeIcon(kindOf(d));
        const colorOf = (d: NodeData): string => (d.data as { color?: string }).color ?? nodeColor(kindOf(d));
        const graph = new Graph({
            container: this.hostEl.nativeElement,
            data: (this.data ?? { nodes: [], edges: [] }) as unknown as GraphData,
            autoFit: 'view',
            node: {
                // Uniform rounded "processor" tile (NiFi style); the category icon + outline distinguish kinds.
                type: 'rect',
                style: {
                    size: [46, 34],
                    radius: 8,
                    fill: nodeFill,
                    // Outline = status colour when set (tested/rejects/unconfigured/dangling), else the
                    // node's configured/category colour.
                    stroke: (d) => nodeStatusStroke(statusOf(d)) ?? colorOf(d),
                    lineWidth: 1.5,
                    // Dashed outline flags the "needs attention" states (unconfigured / dangling); [] = solid.
                    lineDash: (d) => (statusOf(d) === 'unconfigured' || statusOf(d) === 'dangling' ? [4, 3] : []),
                    iconSrc: (d) => iconOf(d),
                    iconWidth: 22,
                    iconHeight: 22,
                    labelText: (d) => statusGlyph(statusOf(d)) + (d.data as { label: string }).label,
                    labelFill: fg,
                    labelFontSize: 11,
                    labelPlacement: 'bottom',
                    cursor: 'pointer',
                },
            },
            edge: {
                type: 'line',
                style: {
                    stroke: edge,
                    endArrow: true,
                    labelText: (d) => (d.data as { kind: string }).kind,
                    labelFill: fg,
                    labelFontSize: 9,
                    labelBackground: false,
                },
            },
            layout: { type: 'antv-dagre', rankdir: 'LR', nodesep: 18, ranksep: 60 },
            behaviors: [
                'drag-canvas',
                'zoom-canvas',
                'click-select',
                {
                    // Plain drag MOVES a node (positions are visual-only; authored flows store no coords).
                    // Gated to plain drag so Shift+drag is free for create-edge below — both share the drag
                    // gesture, so the Shift modifier is what disambiguates move vs. connect.
                    type: 'drag-element',
                    key: 'drag-element',
                    enable: (e: { shiftKey?: boolean; targetType?: string }) =>
                        e?.targetType === 'node' && !e?.shiftKey,
                },
                {
                    // Hold Shift and drag from one node onto another to draw an edge (the inspector's two-click
                    // "Connect" remains the discoverable path). Shift-gated so plain drag can reposition nodes.
                    type: 'create-edge',
                    key: 'create-edge',
                    trigger: 'drag',
                    enable: (e: { shiftKey?: boolean }) => !!e?.shiftKey,
                    style: { stroke: edge, lineWidth: 1.5, endArrow: true, lineDash: [4, 3] },
                    onCreate: (e: { source?: string; target?: string }) => {
                        const source = e?.source;
                        const target = e?.target;
                        if (!source || !target || source === target) return false;
                        this.edgeCreated.emit({ source, target });
                        return { id: `${source}->${target}:data:${Date.now()}`, source, target, data: { kind: 'data' } };
                    },
                },
            ],
        });
        graph.on(NodeEvent.CLICK, (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.nodeSelected.emit(id);
        });
        graph.on('node:dblclick', (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.nodeOpen.emit(id);
        });
        graph.on('edge:click', (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.edgeSelected.emit(id);
        });
        graph.on(CanvasEvent.CLICK, () => this.backgroundClick.emit());
        graph.on('node:pointerenter', (e) => {
            const ev = e as unknown as { target?: { id?: string }; client?: { x: number; y: number } };
            if (ev.target?.id) this.nodeHover.emit({ id: ev.target.id, x: ev.client?.x ?? 0, y: ev.client?.y ?? 0 });
        });
        graph.on('node:pointerleave', () => this.nodeHover.emit(null));
        graph.render();
        this.graph = graph;
    }
}
