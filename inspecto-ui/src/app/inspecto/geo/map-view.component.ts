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
    NgZone,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { GammaConfigService } from '@gamma/services/config';
import maplibregl, { GeoJSONSource, LngLatBounds, Map as MlMap, MapLayerMouseEvent } from 'maplibre-gl';
import type { FeatureCollection, Point } from 'geojson';
import { Protocol } from 'pmtiles';
import { basemapStyle, mapPalette } from 'app/inspecto/theme/map-tokens';
import { ICON_COLOR_SWATCHES } from 'app/inspecto/theme/chart-tokens';
import { greatCircleArc } from './geo-analysis';
import { GeoCamera } from './geo-source';
import { GeoData } from './geo-types';

const EMPTY_FC: FeatureCollection = { type: 'FeatureCollection', features: [] };

/** Register the pmtiles:// protocol once (customer vector-tile archives, Phase 4 seam). */
let pmtilesRegistered = false;
function ensurePmtilesProtocol(): void {
    if (!pmtilesRegistered) {
        maplibregl.addProtocol('pmtiles', new Protocol().tile);
        pmtilesRegistered = true;
    }
}

/** An analysis/search overlay: listed points/routes render full-strength, everything else dims. */
export interface GeoEmphasis {
    pointIds: string[];
    routeIds?: string[];
}

/** How the data plane renders: individual markers (clustered) or a density heatmap. */
export type GeoDisplayMode = 'markers' | 'heatmap';

/** Toggleable map strata (the studio's layer manager). Absent field = visible. */
export interface GeoLayerToggles {
    placeLabels?: boolean;
    boundaries?: boolean;
    pointLabels?: boolean;
}

const POINTS_SOURCE = 'inspecto-points';
const POINTS_RAW_SOURCE = 'inspecto-points-raw'; // unclustered twin feeding the heatmap
const POINTS_LAYER = 'inspecto-points-circles';
const CLUSTERS_LAYER = 'inspecto-points-clusters';
const CLUSTER_COUNT_LAYER = 'inspecto-points-cluster-counts';
const LABELS_LAYER = 'inspecto-points-labels';
const HEATMAP_LAYER = 'inspecto-points-heatmap';
const ROUTES_SOURCE = 'inspecto-routes';
const ROUTES_LAYER = 'inspecto-routes-lines';
const ROUTE_ARROWS_LAYER = 'inspecto-routes-arrows';
const OVERLAY_SOURCE = 'inspecto-overlay';
const OVERLAY_FILL_LAYER = 'inspecto-overlay-fills';
const OVERLAY_LINE_LAYER = 'inspecto-overlay-lines';
const OVERLAY_POINT_LAYER = 'inspecto-overlay-points';
const OVERLAY_LABEL_LAYER = 'inspecto-overlay-labels';
const PLACE_LABEL_LAYERS = ['places-major', 'places-mid', 'places-minor'];

/**
 * Read-only MapLibre GL host for Geo Map Analysis — the map analog of `GraphViewComponent`
 * (same lifecycle discipline: gamma scheme tracking, ResizeObserver, destroy on teardown; map
 * events run outside Angular). Renders the offline bundled basemap (`map-tokens.basemapStyle`)
 * plus the data plane: `GeoData` points as kind-coloured circles with clustering and zoom-in
 * labels (routes land in Phase 2). `emphasis` dims everything but the listed points.
 *
 * Like the G6 hosts, the map cannot instantiate in jsdom (WebGL) — `mount()` no-ops when WebGL
 * is unavailable, so specs (axe) can render the component with any input.
 */
@Component({
    selector: 'inspecto-map-view',
    standalone: true,
    template: '<div #host class="h-full w-full"></div>',
    host: { '[class]': `fill ? 'block w-full min-h-0 flex-auto' : 'block w-full min-h-96 h-[62vh]'` },
})
export class MapViewComponent implements AfterViewInit, OnChanges, OnDestroy {
    /** The data plane; `null` = don't mount the map (parent shows an empty state). */
    @Input({ required: true }) data: GeoData | null = null;
    /** Highlight overlay (search/analysis results); `null` = all full-strength. */
    @Input() emphasis: GeoEmphasis | null = null;
    /** Markers (clustered) or a density heatmap over the same points. */
    @Input() display: GeoDisplayMode = 'markers';
    /** Initial camera (a saved view's); `null` = fit to data on load. */
    @Input() camera: GeoCamera | null = null;
    /**
     * Investigation overlay (measure lines, radius circles, polygons, notes, custom GeoJSON):
     * generic Fill/Line/Point rendering; a feature's `label` property renders as text and its
     * `color` property overrides the accent.
     */
    @Input() overlay: FeatureCollection | null = null;
    /** Layer-manager toggles; `null` = everything visible. */
    @Input() layers: GeoLayerToggles | null = null;
    /** Fill the remaining space of a flex-column parent instead of the fixed 62vh page band. */
    @Input() fill = false;
    @Output() pointClick = new EventEmitter<string>();
    @Output() routeClick = new EventEmitter<string>();
    /** Any map click (tools: measure/radius/polygon/note placement). */
    @Output() mapClick = new EventEmitter<{ lat: number; lon: number }>();

    @ViewChild('host') private hostEl!: ElementRef<HTMLDivElement>;
    private map: MlMap | null = null;
    private dark = false;
    private ready = false;
    private resizeObserver: ResizeObserver | null = null;
    private destroyRef = inject(DestroyRef);
    private zone = inject(NgZone);

    constructor() {
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((config) => {
                this.dark =
                    config?.scheme === 'dark' ||
                    (config?.scheme === 'auto' &&
                        window.matchMedia('(prefers-color-scheme: dark)').matches);
                if (this.ready) this.applyScheme();
            });
    }

    ngAfterViewInit(): void {
        this.ready = true;
        this.sync();
        if (typeof ResizeObserver !== 'undefined') {
            this.resizeObserver = new ResizeObserver(() => this.map?.resize());
            this.resizeObserver.observe(this.hostEl.nativeElement);
        }
    }

    ngOnChanges(): void {
        if (this.ready) this.sync();
    }

    ngOnDestroy(): void {
        this.resizeObserver?.disconnect();
        this.map?.remove();
        this.map = null;
    }

    /** The rendered map as a PNG data-URI (export), or `null` before the first render. */
    exportPng(): string | null {
        return this.map ? this.map.getCanvas().toDataURL('image/png') : null;
    }

    /** Fit the viewport to the data plane (toolbar fit-to-data); world view when empty. */
    fitToData(): void {
        if (!this.map) return;
        const pts = this.data?.points ?? [];
        if (!pts.length) {
            this.map.jumpTo({ center: [10, 20], zoom: 1.2 });
            return;
        }
        const bounds = new LngLatBounds();
        for (const p of pts) bounds.extend([p.lon, p.lat]);
        this.map.fitBounds(bounds, { padding: 48, maxZoom: 12, duration: 300 });
    }

    /** Center on one point (results-list / data-row click-to-focus). */
    flyTo(pointId: string): void {
        const p = this.data?.points.find((x) => x.id === pointId);
        if (!p || !this.map) return;
        this.map.easeTo({ center: [p.lon, p.lat], zoom: Math.max(this.map.getZoom(), 10), duration: 400 });
    }

    /** Jump to a saved view's camera (also applied automatically on mount via `[camera]`). */
    setCamera(camera: GeoCamera): void {
        this.map?.jumpTo({ center: camera.center, zoom: camera.zoom });
    }

    /** The current camera (captured with a saved view), or `null` before the first render. */
    getCamera(): GeoCamera | null {
        if (!this.map) return null;
        const c = this.map.getCenter();
        return { center: [c.lng, c.lat], zoom: this.map.getZoom() };
    }

    /** The current viewport as [west, south, east, north] (the studio's filter-to-view). */
    getViewBounds(): [number, number, number, number] | null {
        const b = this.map?.getBounds();
        return b ? [b.getWest(), b.getSouth(), b.getEast(), b.getNorth()] : null;
    }

    /** Stable kind → swatch colour for the current data (shared with the studio's legend). */
    kindColors(): Map<string, string> {
        const kinds = [...new Set((this.data?.points ?? []).map((p) => p.kind))].sort();
        return new Map(kinds.map((k, i) => [k, ICON_COLOR_SWATCHES[i % ICON_COLOR_SWATCHES.length]]));
    }

    private static webglAvailable(): boolean {
        try {
            const c = document.createElement('canvas');
            return !!(c.getContext('webgl2') ?? c.getContext('webgl'));
        } catch {
            return false;
        }
    }

    private sync(): void {
        if (!this.data) {
            this.map?.remove();
            this.map = null;
            return;
        }
        if (!this.map) {
            this.mount();
        } else {
            this.applyData();
        }
    }

    private mount(): void {
        if (!MapViewComponent.webglAvailable()) return; // jsdom / headless: stay unmounted
        ensurePmtilesProtocol();
        this.zone.runOutsideAngular(() => {
            const map = new maplibregl.Map({
                container: this.hostEl.nativeElement,
                style: basemapStyle(this.dark),
                center: [10, 20],
                zoom: 1.2,
                attributionControl: { compact: true },
                // Needed so exportPng() can read the WebGL canvas (v5: a context attribute).
                canvasContextAttributes: { preserveDrawingBuffer: true },
            });
            // Resource/style failures are otherwise silent — surface them for diagnosis.
            map.on('error', (e) => console.error('[inspecto-map-view]', e.error ?? e));
            map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
            map.addControl(new maplibregl.FullscreenControl(), 'top-right');
            map.addControl(new maplibregl.ScaleControl({}), 'bottom-left');
            map.on('load', () => {
                this.addDataLayers(map);
                if (this.camera) map.jumpTo({ center: this.camera.center, zoom: this.camera.zoom });
                else this.fitToData();
            });
            map.on('click', POINTS_LAYER, (e: MapLayerMouseEvent) => {
                const id = e.features?.[0]?.properties?.['id'];
                if (id != null) this.zone.run(() => this.pointClick.emit(String(id)));
            });
            map.on('click', CLUSTERS_LAYER, async (e: MapLayerMouseEvent) => {
                const f = e.features?.[0];
                if (!f) return;
                const src = map.getSource(POINTS_SOURCE) as GeoJSONSource;
                const zoom = await src.getClusterExpansionZoom(Number(f.properties?.['cluster_id']));
                map.easeTo({ center: (f.geometry as Point).coordinates as [number, number], zoom });
            });
            map.on('click', ROUTES_LAYER, (e: MapLayerMouseEvent) => {
                const id = e.features?.[0]?.properties?.['id'];
                if (id != null) this.zone.run(() => this.routeClick.emit(String(id)));
            });
            map.on('click', (e) => {
                this.zone.run(() => this.mapClick.emit({ lat: e.lngLat.lat, lon: e.lngLat.lng }));
            });
            for (const layer of [POINTS_LAYER, CLUSTERS_LAYER, ROUTES_LAYER]) {
                map.on('mouseenter', layer, () => (map.getCanvas().style.cursor = 'pointer'));
                map.on('mouseleave', layer, () => (map.getCanvas().style.cursor = ''));
            }
            this.map = map;
        });
    }

    private pointsGeoJson(): FeatureCollection {
        const colors = this.kindColors();
        const em = this.emphasis ? new Set(this.emphasis.pointIds) : null;
        return {
            type: 'FeatureCollection',
            features: (this.data?.points ?? []).map((p) => ({
                type: 'Feature',
                geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
                properties: {
                    id: p.id,
                    kind: p.kind,
                    label: p.label ?? p.id,
                    color: colors.get(p.kind),
                    // 1 = highlighted, -1 = dimmed, 0 = no overlay active
                    em: em ? (em.has(p.id) ? 1 : -1) : 0,
                },
            })),
        };
    }

    /**
     * Routes as great-circle LineStrings between their endpoint points. Routes sharing an
     * endpoint pair (parallel/antiparallel corridors) bow apart so both stay clickable.
     */
    private routesGeoJson(): FeatureCollection {
        const byId = new Map((this.data?.points ?? []).map((p) => [p.id, p]));
        const colors = this.kindColors();
        const em = this.emphasis?.routeIds ? new Set(this.emphasis.routeIds) : null;
        const kinds = [...new Set((this.data?.routes ?? []).map((r) => r.kind))].sort();
        const kindColor = new Map(kinds.map((k, i) => [k, ICON_COLOR_SWATCHES[(colors.size + i) % ICON_COLOR_SWATCHES.length]]));
        const pairSeen = new Map<string, number>();
        return {
            type: 'FeatureCollection',
            features: (this.data?.routes ?? []).flatMap((r) => {
                const a = byId.get(r.from);
                const b = byId.get(r.to);
                if (!a || !b) return [];
                const pairKey = [r.from, r.to].sort().join('|');
                const dup = pairSeen.get(pairKey) ?? 0;
                pairSeen.set(pairKey, dup + 1);
                let coords = greatCircleArc(a.lat, a.lon, b.lat, b.lon);
                if (dup > 0) {
                    // Bow the duplicate perpendicular to the chord, strongest mid-arc.
                    const nx = -(b.lat - a.lat);
                    const ny = b.lon - a.lon;
                    const len = Math.hypot(nx, ny) || 1;
                    const amp = 0.06 * Math.ceil(dup / 2) * (dup % 2 ? 1 : -1);
                    coords = coords.map(([lon, lat], i) => {
                        const f = Math.sin((i / (coords.length - 1)) * Math.PI) * amp;
                        return [lon + (nx / len) * f, lat + (ny / len) * f] as [number, number];
                    });
                }
                return [{
                    type: 'Feature' as const,
                    geometry: { type: 'LineString' as const, coordinates: coords },
                    properties: {
                        id: r.id,
                        kind: r.kind,
                        label: r.label ?? r.kind,
                        color: kindColor.get(r.kind),
                        weight: r.weight ?? 1,
                        em: em ? (em.has(r.id) ? 1 : -1) : 0,
                    },
                }];
            }),
        };
    }

    private addDataLayers(map: MlMap): void {
        const palette = mapPalette(this.dark);
        map.addSource(POINTS_SOURCE, {
            type: 'geojson',
            data: this.pointsGeoJson(),
            cluster: true,
            clusterRadius: 42,
            clusterMaxZoom: 14,
        });
        map.addSource(POINTS_RAW_SOURCE, { type: 'geojson', data: this.pointsGeoJson() });
        map.addSource(ROUTES_SOURCE, { type: 'geojson', data: this.routesGeoJson() });
        // Routes render under the point markers.
        map.addLayer({
            id: ROUTES_LAYER,
            type: 'line',
            source: ROUTES_SOURCE,
            layout: { 'line-cap': 'round' },
            paint: {
                'line-color': ['coalesce', ['get', 'color'], palette.point],
                'line-width': ['min', 8, ['+', 1, ['log2', ['+', ['get', 'weight'], 1]]]],
                'line-opacity': ['case', ['==', ['get', 'em'], -1], 0.15, 0.8],
            },
        });
        map.addLayer({
            id: ROUTE_ARROWS_LAYER,
            type: 'symbol',
            source: ROUTES_SOURCE,
            layout: {
                'symbol-placement': 'line',
                'symbol-spacing': 120,
                // '>' is in the bundled 0-255 glyph range; keep-upright off so it points along the line.
                'text-field': '>',
                'text-font': ['Noto Sans Regular'],
                'text-size': 14,
                'text-keep-upright': false,
                'text-allow-overlap': true,
            },
            paint: {
                'text-color': ['coalesce', ['get', 'color'], palette.point],
                'text-opacity': ['case', ['==', ['get', 'em'], -1], 0.15, 0.9],
            },
        });
        map.addLayer({
            id: HEATMAP_LAYER,
            type: 'heatmap',
            source: POINTS_RAW_SOURCE,
            layout: { visibility: this.display === 'heatmap' ? 'visible' : 'none' },
            paint: {
                'heatmap-radius': ['interpolate', ['linear'], ['zoom'], 2, 12, 12, 28],
                'heatmap-opacity': 0.75,
            },
        });
        map.addLayer({
            id: CLUSTERS_LAYER,
            type: 'circle',
            source: POINTS_SOURCE,
            filter: ['has', 'point_count'],
            paint: {
                'circle-color': palette.point,
                'circle-opacity': 0.85,
                'circle-radius': ['step', ['get', 'point_count'], 12, 20, 16, 100, 22],
                'circle-stroke-color': palette.pointStroke,
                'circle-stroke-width': 1.5,
            },
        });
        map.addLayer({
            id: CLUSTER_COUNT_LAYER,
            type: 'symbol',
            source: POINTS_SOURCE,
            filter: ['has', 'point_count'],
            layout: {
                'text-field': ['get', 'point_count_abbreviated'],
                'text-font': ['Noto Sans Regular'],
                'text-size': 11,
            },
            paint: { 'text-color': palette.pointStroke },
        });
        map.addLayer({
            id: POINTS_LAYER,
            type: 'circle',
            source: POINTS_SOURCE,
            filter: ['!', ['has', 'point_count']],
            paint: {
                'circle-color': ['coalesce', ['get', 'color'], palette.point],
                'circle-radius': ['case', ['==', ['get', 'em'], 1], 8, 5],
                'circle-opacity': ['case', ['==', ['get', 'em'], -1], 0.25, 1],
                'circle-stroke-color': palette.pointStroke,
                'circle-stroke-width': ['case', ['==', ['get', 'em'], 1], 2.5, 1.5],
                'circle-stroke-opacity': ['case', ['==', ['get', 'em'], -1], 0.25, 1],
            },
        });
        map.addLayer({
            id: LABELS_LAYER,
            type: 'symbol',
            source: POINTS_SOURCE,
            filter: ['!', ['has', 'point_count']],
            minzoom: 9,
            layout: {
                'text-field': ['get', 'label'],
                'text-font': ['Noto Sans Regular'],
                'text-size': 11,
                'text-anchor': 'top',
                'text-offset': [0, 0.6],
                'text-optional': true,
            },
            paint: {
                'text-color': palette.placeText,
                'text-halo-color': palette.placeHalo,
                'text-halo-width': 1,
                'text-opacity': ['case', ['==', ['get', 'em'], -1], 0.35, 1],
            },
        });
        // Investigation overlay (tools/notes/custom GeoJSON) — always on top of the data plane.
        map.addSource(OVERLAY_SOURCE, { type: 'geojson', data: this.overlay ?? EMPTY_FC });
        map.addLayer({
            id: OVERLAY_FILL_LAYER,
            type: 'fill',
            source: OVERLAY_SOURCE,
            filter: ['==', ['geometry-type'], 'Polygon'],
            paint: {
                'fill-color': ['coalesce', ['get', 'color'], palette.point],
                'fill-opacity': 0.12,
            },
        });
        map.addLayer({
            id: OVERLAY_LINE_LAYER,
            type: 'line',
            source: OVERLAY_SOURCE,
            filter: ['in', ['geometry-type'], ['literal', ['LineString', 'Polygon']]],
            layout: { 'line-cap': 'round' },
            paint: {
                'line-color': ['coalesce', ['get', 'color'], palette.point],
                'line-width': 2,
                'line-dasharray': [2, 1.5],
            },
        });
        map.addLayer({
            id: OVERLAY_POINT_LAYER,
            type: 'circle',
            source: OVERLAY_SOURCE,
            filter: ['==', ['geometry-type'], 'Point'],
            paint: {
                'circle-radius': 4,
                'circle-color': ['coalesce', ['get', 'color'], palette.point],
                'circle-stroke-color': palette.pointStroke,
                'circle-stroke-width': 1.5,
            },
        });
        map.addLayer({
            id: OVERLAY_LABEL_LAYER,
            type: 'symbol',
            source: OVERLAY_SOURCE,
            filter: ['has', 'label'],
            layout: {
                'text-field': ['get', 'label'],
                'text-font': ['Noto Sans Regular'],
                'text-size': 12,
                'text-anchor': 'bottom',
                'text-offset': [0, -0.6],
                'text-allow-overlap': true,
            },
            paint: {
                'text-color': palette.placeText,
                'text-halo-color': palette.placeHalo,
                'text-halo-width': 1.2,
            },
        });
        this.applyDisplayMode();
        this.applyLayerToggles();
    }

    /** Layer-manager visibility (basemap place labels, boundaries, data-point labels). */
    private applyLayerToggles(): void {
        const map = this.map;
        if (!map) return;
        const set = (ids: string[], on: boolean | undefined): void => {
            for (const id of ids) {
                if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', on === false ? 'none' : 'visible');
            }
        };
        set(PLACE_LABEL_LAYERS, this.layers?.placeLabels);
        set(['boundaries'], this.layers?.boundaries);
        // Point labels also obey the heatmap swap — only force-hide, never force-show, here.
        if (this.layers?.pointLabels === false) set([LABELS_LAYER], false);
        else if (this.display === 'markers') set([LABELS_LAYER], true);
    }

    private applyData(): void {
        const points = this.pointsGeoJson();
        for (const id of [POINTS_SOURCE, POINTS_RAW_SOURCE]) {
            (this.map?.getSource(id) as GeoJSONSource | undefined)?.setData(points);
        }
        (this.map?.getSource(ROUTES_SOURCE) as GeoJSONSource | undefined)?.setData(this.routesGeoJson());
        (this.map?.getSource(OVERLAY_SOURCE) as GeoJSONSource | undefined)?.setData(this.overlay ?? EMPTY_FC);
        this.applyDisplayMode();
        this.applyLayerToggles();
    }

    /** Heatmap swaps in for the marker/cluster layers; routes stay visible in both modes. */
    private applyDisplayMode(): void {
        const map = this.map;
        if (!map?.getLayer(HEATMAP_LAYER)) return;
        const heat = this.display === 'heatmap';
        map.setLayoutProperty(HEATMAP_LAYER, 'visibility', heat ? 'visible' : 'none');
        for (const id of [POINTS_LAYER, CLUSTERS_LAYER, CLUSTER_COUNT_LAYER, LABELS_LAYER]) {
            map.setLayoutProperty(id, 'visibility', heat ? 'none' : 'visible');
        }
    }

    private applyScheme(): void {
        if (!this.map) return;
        this.map.setStyle(basemapStyle(this.dark));
        // setStyle drops custom sources/layers — re-add the data plane once the style loads.
        this.map.once('styledata', () => {
            if (this.map && !this.map.getSource(POINTS_SOURCE)) this.addDataLayers(this.map);
        });
    }
}
