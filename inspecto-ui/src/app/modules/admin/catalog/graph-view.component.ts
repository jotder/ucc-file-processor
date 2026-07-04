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
import { EdgeData, EdgeEvent, ElementDatum, Graph, GraphData, NodeData, NodeEvent } from '@antv/g6';
import { G6GraphData, nodeColor, nodeShape } from './catalog-graph';
import { NodeKind } from 'app/inspecto/api';
import { ICON_COLOR_SWATCHES, canvasTheme } from 'app/inspecto/theme/chart-tokens';

/**
 * An analysis-result overlay (Link Analysis Studio): listed nodes/edges render full-strength (or
 * group-coloured), everything else dims. `groups` maps nodeId → a group key (e.g. a community id);
 * each distinct key gets a swatch colour. `null` = no emphasis (all full-strength).
 */
export interface GraphEmphasis {
    nodeIds: string[];
    edgeIds?: string[];
    groups?: Map<string, string>;
}

/** Line pattern for a relationship kind (Link Analysis "Display" menu). */
export type EdgePattern = 'solid' | 'dashed' | 'dotted';

/**
 * Optional presentation overrides (Link Analysis Studio "Display" menu — persisted with a saved
 * view): label visibility plus per-kind colour/shape/pattern/size overrides. Edge kinds match on
 * the base kind (the projection's folded `calls · 2` styles as `calls`). `null` = the built-in
 * defaults.
 */
export interface GraphDisplayOptions {
    nodeLabels: boolean;
    edgeLabels: boolean;
    /** node kind → stroke colour (a chart-token swatch). */
    nodeColors: Record<string, string>;
    /** edge (relationship) kind → stroke colour. */
    edgeColors: Record<string, string>;
    /** node kind → G6 shape name (the per-kind "icon"). */
    nodeShapes: Record<string, string>;
    /** edge (relationship) kind → line pattern. */
    edgePatterns: Record<string, EdgePattern>;
    /** edge (relationship) kind → line width in px. */
    edgeSizes: Record<string, number>;
}

/** The node shapes the Display menu offers per kind (value = G6 node type; glyph = the picker face). */
export const GRAPH_NODE_SHAPES: readonly { value: string; glyph: string; label: string }[] = [
    { value: 'circle', glyph: '●', label: 'Circle' },
    { value: 'rect', glyph: '■', label: 'Square' },
    { value: 'diamond', glyph: '◆', label: 'Diamond' },
    { value: 'triangle', glyph: '▲', label: 'Triangle' },
    { value: 'star', glyph: '★', label: 'Star' },
    { value: 'hexagon', glyph: '⬢', label: 'Hexagon' },
];

/** The line patterns the Display menu offers per relationship kind. */
export const GRAPH_EDGE_PATTERNS: readonly { value: EdgePattern; glyph: string; label: string }[] = [
    { value: 'solid', glyph: '—', label: 'Solid' },
    { value: 'dashed', glyph: '╌', label: 'Dashed' },
    { value: 'dotted', glyph: '⋯', label: 'Dotted' },
];

/** The line widths the Display menu offers per relationship kind. */
export const GRAPH_EDGE_SIZES: readonly { value: number; label: string }[] = [
    { value: 1.5, label: 'S' },
    { value: 3, label: 'M' },
    { value: 5, label: 'L' },
];

/** The G6 `lineDash` array for a pattern; solid (or unset) = a solid stroke (`undefined`). */
export function edgeDash(pattern: EdgePattern | undefined): number[] | undefined {
    return pattern === 'dashed' ? [6, 4] : pattern === 'dotted' ? [1, 3] : undefined;
}

/** The relationship kind an edge styles by — the folded-count suffix (`calls · 2`) stripped. */
export function baseEdgeKind(kind: unknown): string {
    return String(kind ?? '').split(' · ')[0];
}

const esc = (s: unknown): string =>
    String(s ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

/**
 * Read-only AntV G6 host for the catalog metadata graph: layered (dagre) layout,
 * pan/zoom, per-kind shapes/outline colours, node-click emits the node id.
 * Recreated when the data or the gamma colour scheme changes.
 */
@Component({
    selector: 'inspecto-graph-view',
    standalone: true,
    template: '<div #host class="h-full w-full"></div>',
    // Default: viewport-dynamic height for scrolling pages. `fill` mode instead grows into the
    // remaining space of a flex-column studio (Link Analysis); autoFit:'view' scales the graph.
    host: { '[class]': `fill ? 'block w-full min-h-0 flex-auto' : 'block w-full min-h-96 h-[62vh]'` },
})
export class GraphViewComponent implements AfterViewInit, OnChanges, OnDestroy {
    @Input({ required: true }) data: G6GraphData | null = null;
    @Input() emphasis: GraphEmphasis | null = null;
    /** Fill the remaining space of a flex-column parent instead of the fixed 62vh page band. */
    @Input() fill = false;
    /** Presentation overrides (labels on/off, per-kind colours); `null` = defaults. */
    @Input() display: GraphDisplayOptions | null = null;
    /** Enable hover tooltips with short node/edge details (Link Analysis). */
    @Input() tooltips = false;
    @Output() nodeClick = new EventEmitter<string>();
    @Output() edgeClick = new EventEmitter<string>();

    @ViewChild('host') private hostEl!: ElementRef<HTMLDivElement>;
    private graph: Graph | null = null;
    private dark = false;
    private ready = false;
    private resizeObserver: ResizeObserver | null = null;
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
        // Track container size (collapsible side panes resize the canvas live). Absent in jsdom.
        if (typeof ResizeObserver !== 'undefined') {
            this.resizeObserver = new ResizeObserver(() => this.graph?.resize());
            this.resizeObserver.observe(this.hostEl.nativeElement);
        }
    }

    ngOnChanges(): void {
        if (this.ready) this.rebuild();
    }

    ngOnDestroy(): void {
        this.resizeObserver?.disconnect();
        this.graph?.destroy();
    }

    /** The rendered canvas as a PNG data-URI (Link Analysis export), or `null` before the first render. */
    exportPng(): Promise<string> | null {
        return this.graph ? this.graph.toDataURL({ type: 'image/png' }) : null;
    }

    /** Re-fit the whole graph into the viewport (the toolbar's fit-to-screen). */
    fitView(): void {
        void this.graph?.fitView();
    }

    /** Short hover details: node → label/kind/degree, edge → kind + endpoint labels. */
    private tooltipHtml(items: ElementDatum[]): string {
        const d = items[0];
        if (!d) return '';
        const data = (d.data ?? {}) as { label?: string; kind?: string };
        if ('source' in d && 'target' in d) {
            const label = (id: unknown): string =>
                (this.data?.nodes.find((n) => n.id === id)?.data.label ?? String(id)) as string;
            return `<b>${esc(data.kind)}</b><br/>${esc(label((d as EdgeData).source))} → ${esc(label((d as EdgeData).target))}`;
        }
        const degree = this.data?.edges.filter((e) => e.source === d.id || e.target === d.id).length ?? 0;
        return `<b>${esc(data.label)}</b><br/>${esc(data.kind)} · ${degree} link${degree === 1 ? '' : 's'}`;
    }

    private rebuild(): void {
        this.graph?.destroy();
        this.graph = null;
        if (!this.data?.nodes.length) return;
        const { fg, surface: nodeFill, edge } = canvasTheme(this.dark);
        const kindOf = (d: NodeData): NodeKind => (d.data as { kind: NodeKind }).kind;
        const iconOf = (d: NodeData): string | undefined => (d.data as { iconSrc?: string }).iconSrc;
        // Emphasis overlay: swatch per distinct group key; non-listed elements dim.
        const em = this.emphasis;
        const emNodes = em ? new Set(em.nodeIds) : null;
        const emEdges = em?.edgeIds ? new Set(em.edgeIds) : null;
        const groupSwatch = new Map<string, string>();
        for (const g of em?.groups?.values() ?? []) {
            if (!groupSwatch.has(g)) groupSwatch.set(g, ICON_COLOR_SWATCHES[groupSwatch.size % ICON_COLOR_SWATCHES.length]);
        }
        const nodeDim = (id: string): boolean => !!emNodes && !emNodes.has(id) && !em?.groups?.has(id);
        const display = this.display;
        const colorOf = (d: NodeData): string => {
            const group = em?.groups?.get(d.id as string);
            if (group) return groupSwatch.get(group)!; // analysis overlay wins over styling
            return display?.nodeColors[kindOf(d)] ?? (d.data as { color?: string }).color ?? nodeColor(kindOf(d));
        };
        const edgeColorOf = (d: EdgeData): string =>
            display?.edgeColors[baseEdgeKind((d.data as { kind?: string }).kind)] ?? edge;
        const graph = new Graph({
            container: this.hostEl.nativeElement,
            data: this.data as unknown as GraphData,
            autoFit: 'view',
            node: {
                // Icon tile (rounded rect + glyph) when the data carries a resolved icon (flow/pipeline views);
                // otherwise the per-kind shape (the catalog metadata graph).
                type: (d) => (iconOf(d) ? 'rect' : (display?.nodeShapes[kindOf(d)] ?? nodeShape(kindOf(d)))),
                style: {
                    size: (d) => (iconOf(d) ? [46, 34] : 32),
                    radius: 8,
                    fill: nodeFill,
                    stroke: (d) => colorOf(d),
                    lineWidth: 2,
                    iconSrc: (d) => iconOf(d),
                    iconWidth: 22,
                    iconHeight: 22,
                    labelText: display?.nodeLabels === false ? undefined : (d): string => (d.data as { label: string }).label,
                    labelFill: fg,
                    labelFontSize: 11,
                    labelPlacement: 'bottom',
                    cursor: 'pointer',
                    opacity: (d) => (nodeDim(d.id as string) ? 0.25 : 1),
                    labelOpacity: (d) => (nodeDim(d.id as string) ? 0.35 : 1),
                },
            },
            edge: {
                type: 'line',
                style: {
                    stroke: (d) => edgeColorOf(d),
                    opacity: (d) => (emEdges ? (emEdges.has(d.id as string) ? 1 : 0.2) : 1),
                    endArrow: true,
                    lineDash: (d) => edgeDash(display?.edgePatterns[baseEdgeKind((d.data as { kind?: string }).kind)]),
                    // Per-kind size override wins; else the optional data-plane weight (T22 provenance
                    // overlay) scales the line width log-style; else the default (catalog/combined views).
                    lineWidth: (d) => {
                        const override = display?.edgeSizes[baseEdgeKind((d.data as { kind?: string }).kind)];
                        if (override) return override;
                        const w = (d.data as { weight?: number }).weight;
                        return w && w > 0 ? Math.min(12, 1.5 + Math.log2(w + 1)) : 1.5;
                    },
                    labelText: display?.edgeLabels === false ? undefined : (d): string => (d.data as { kind: string }).kind,
                    labelFill: fg,
                    labelFontSize: 9,
                    labelBackground: false,
                },
            },
            layout: { type: 'antv-dagre', rankdir: 'LR', nodesep: 18, ranksep: 60 },
            behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
            plugins: this.tooltips
                ? [{ type: 'tooltip', trigger: 'hover', getContent: async (_e: unknown, items: ElementDatum[]) => this.tooltipHtml(items) }]
                : [],
        });
        graph.on(NodeEvent.CLICK, (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.nodeClick.emit(id);
        });
        graph.on(EdgeEvent.CLICK, (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.edgeClick.emit(id);
        });
        graph.render();
        this.graph = graph;
    }
}
