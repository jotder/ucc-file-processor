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
import { G6GraphData, nodeColor, nodeShape } from 'app/modules/admin/catalog/catalog-graph';
import { NodeKind } from 'app/inspecto/api';
import { canvasTheme } from 'app/inspecto/theme/chart-tokens';

/**
 * Interactive AntV G6 host for the flow editor. Unlike the read-only {@link GraphViewComponent} (which
 * `destroy()`s + rebuilds on every data change), this host keeps a **persistent** graph and mutates it in
 * place ({@link addNode}/{@link addEdge}/{@link removeElement}/{@link updateNodeLabel}) so user-arranged
 * positions survive edits. It only rebuilds when {@link graphKey} (the selected flow id) changes or the
 * colour scheme flips. Authored flows store no coordinates, so node moves are purely visual (not emitted).
 *
 * <p>Edge creation is **two-click** (driven by the parent via {@link nodeSelected}), deliberately avoiding
 * G6 v5's `create-edge` drag behavior. New nodes arrive by HTML5 drag-drop from the palette
 * ({@link dropAdd}); Delete/Backspace removes the selection ({@link deleteKey}).
 */
@Component({
    selector: 'app-flow-editor-graph',
    standalone: true,
    template: `<div
        #host
        class="h-full w-full focus:outline-none"
        tabindex="0"
        role="application"
        aria-label="Flow editor canvas — drag a node type from the palette, click two nodes to connect, Delete to remove"
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
    @Output() edgeSelected = new EventEmitter<string>();
    @Output() backgroundClick = new EventEmitter<void>();
    @Output() dropAdd = new EventEmitter<{ type: string; x: number; y: number }>();
    @Output() deleteKey = new EventEmitter<void>();

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
        const graph = new Graph({
            container: this.hostEl.nativeElement,
            data: (this.data ?? { nodes: [], edges: [] }) as unknown as GraphData,
            autoFit: 'view',
            node: {
                type: (d) => nodeShape(kindOf(d)),
                style: {
                    size: 32,
                    fill: nodeFill,
                    stroke: (d) => nodeColor(kindOf(d)),
                    lineWidth: 2,
                    labelText: (d) => (d.data as { label: string }).label,
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
            behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element', 'click-select'],
        });
        graph.on(NodeEvent.CLICK, (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.nodeSelected.emit(id);
        });
        graph.on('edge:click', (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.edgeSelected.emit(id);
        });
        graph.on(CanvasEvent.CLICK, () => this.backgroundClick.emit());
        graph.render();
        this.graph = graph;
    }
}
