import {
    AfterViewInit,
    Component,
    DestroyRef,
    ElementRef,
    EventEmitter,
    Input,
    OnChanges,
    OnDestroy,
    Output,
    ViewChild,
    inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { GammaConfigService } from '@gamma/services/config';
import { Graph, GraphData, NodeData, NodeEvent } from '@antv/g6';
import { G6GraphData, nodeColor, nodeShape } from './catalog-graph';
import { NodeKind } from 'app/ucc/api';

/**
 * Read-only AntV G6 host for the catalog metadata graph: layered (dagre) layout,
 * pan/zoom, per-kind shapes/outline colours, node-click emits the node id.
 * Recreated when the data or the gamma colour scheme changes.
 */
@Component({
    selector: 'ucc-graph-view',
    standalone: true,
    template: '<div #host class="h-full w-full"></div>',
    host: { class: 'block h-160 w-full' },
})
export class GraphViewComponent implements AfterViewInit, OnChanges, OnDestroy {
    @Input({ required: true }) data: G6GraphData | null = null;
    @Output() nodeClick = new EventEmitter<string>();

    @ViewChild('host') private hostEl!: ElementRef<HTMLDivElement>;
    private graph: Graph | null = null;
    private dark = false;
    private ready = false;
    private destroyRef = inject(DestroyRef);

    constructor() {
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((config) => {
                this.dark =
                    config?.scheme === 'dark' ||
                    (config?.scheme === 'auto' &&
                        window.matchMedia('(prefers-color-scheme: dark)').matches);
                if (this.ready) this.rebuild();
            });
    }

    ngAfterViewInit(): void {
        this.ready = true;
        this.rebuild();
    }

    ngOnChanges(): void {
        if (this.ready) this.rebuild();
    }

    ngOnDestroy(): void {
        this.graph?.destroy();
    }

    private rebuild(): void {
        this.graph?.destroy();
        this.graph = null;
        if (!this.data?.nodes.length) return;
        const fg = this.dark ? '#cbd5e1' : '#334155';
        const nodeFill = this.dark ? '#1e293b' : '#ffffff';
        const edge = this.dark ? '#64748b' : '#94a3b8';
        const kindOf = (d: NodeData): NodeKind => (d.data as { kind: NodeKind }).kind;
        const graph = new Graph({
            container: this.hostEl.nativeElement,
            data: this.data as unknown as GraphData,
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
            behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
        });
        graph.on(NodeEvent.CLICK, (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.nodeClick.emit(id);
        });
        graph.render();
        this.graph = graph;
    }
}
