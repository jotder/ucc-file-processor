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
import { NodeKind } from 'app/inspecto/api';
import { canvasTheme } from 'app/inspecto/theme/chart-tokens';

/**
 * Read-only AntV G6 host for the catalog metadata graph: layered (dagre) layout,
 * pan/zoom, per-kind shapes/outline colours, node-click emits the node id.
 * Recreated when the data or the gamma colour scheme changes.
 */
@Component({
    selector: 'inspecto-graph-view',
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
        const { fg, surface: nodeFill, edge } = canvasTheme(this.dark);
        const kindOf = (d: NodeData): NodeKind => (d.data as { kind: NodeKind }).kind;
        const iconOf = (d: NodeData): string | undefined => (d.data as { iconSrc?: string }).iconSrc;
        const colorOf = (d: NodeData): string => (d.data as { color?: string }).color ?? nodeColor(kindOf(d));
        const graph = new Graph({
            container: this.hostEl.nativeElement,
            data: this.data as unknown as GraphData,
            autoFit: 'view',
            node: {
                // Icon tile (rounded rect + glyph) when the data carries a resolved icon (flow/pipeline views);
                // otherwise the per-kind shape (the catalog metadata graph).
                type: (d) => (iconOf(d) ? 'rect' : nodeShape(kindOf(d))),
                style: {
                    size: (d) => (iconOf(d) ? [46, 34] : 32),
                    radius: 8,
                    fill: nodeFill,
                    stroke: (d) => colorOf(d),
                    lineWidth: 2,
                    iconSrc: (d) => iconOf(d),
                    iconWidth: 22,
                    iconHeight: 22,
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
                    // Optional data-plane weight (T22 provenance overlay) scales the line width log-style;
                    // absent ⇒ the default width, so the catalog / combined views are unaffected.
                    lineWidth: (d) => {
                        const w = (d.data as { weight?: number }).weight;
                        return w && w > 0 ? Math.min(12, 1.5 + Math.log2(w + 1)) : 1.5;
                    },
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
