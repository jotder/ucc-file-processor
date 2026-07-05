import type { FilterSpecification, StyleSpecification } from 'maplibre-gl';

/**
 * Concrete colour tokens + style builder for the MapLibre basemap (Geo Map Analysis). Like
 * Chart.js/G6 ({@link ./chart-tokens}), the WebGL map canvas can't resolve CSS custom properties,
 * so the scheme-aware colours are pinned here — the sanctioned place to hardcode map colours.
 *
 * The basemap is fully offline: slimmed Natural Earth 1:50m GeoJSON layers + Noto Sans glyph
 * ranges bundled under `assets/basemap/` (no tile server, no network). A customer-supplied
 * `pmtiles://` vector-tile archive can replace it later (Phase 4) without touching consumers.
 */

interface MapPalette {
    water: string;
    land: string;
    boundary: string;
    placeText: string;
    placeHalo: string;
    /** Accent for data-plane points until per-kind styling lands (P1). */
    point: string;
    pointStroke: string;
}

const LIGHT: MapPalette = {
    water: '#cfdeeb',
    land: '#f1f5f9',
    boundary: '#94a3b8',
    placeText: '#334155',
    placeHalo: '#f8fafc',
    point: '#4f46e5',
    pointStroke: '#ffffff',
};

const DARK: MapPalette = {
    water: '#0b1220',
    land: '#1e293b',
    boundary: '#475569',
    placeText: '#cbd5e1',
    placeHalo: '#0f172a',
    point: '#818cf8',
    pointStroke: '#0f172a',
};

export function mapPalette(dark: boolean): MapPalette {
    return dark ? DARK : LIGHT;
}

/** The offline basemap style: land / lakes / country boundaries / place labels, scheme-aware. */
export function basemapStyle(dark: boolean, assetBase?: string, tileServerUrl?: string | null): StyleSpecification {
    // Inline styles need absolute resource URLs (no style URL to resolve relative paths against).
    assetBase ??= new URL('assets/basemap', document.baseURI).toString();
    const p = mapPalette(dark);
    const src = (file: string): { type: 'geojson'; data: string; attribution?: string } => ({
        type: 'geojson',
        data: `${assetBase}/${file}`,
    });
    return {
        version: 8,
        glyphs: `${assetBase}/fonts/{fontstack}/{range}.pbf`,
        sources: {
            land: { ...src('land.geojson'), attribution: 'Natural Earth' },
            lakes: src('lakes.geojson'),
            boundaries: src('boundaries.geojson'),
            places: src('places.geojson'),
            // The Phase 4 tile-server seam (Settings → Map): a customer {z}/{x}/{y} raster template
            // (satellite etc.) replaces the offline land/lake fills; boundaries/labels stay on top.
            ...(tileServerUrl
                ? { imagery: { type: 'raster' as const, tiles: [tileServerUrl], tileSize: 256, attribution: 'Customer tile server' } }
                : {}),
        },
        layers: [
            { id: 'background', type: 'background', paint: { 'background-color': p.water } },
            ...(tileServerUrl
                ? [{ id: 'imagery', type: 'raster' as const, source: 'imagery' }]
                : [
                      { id: 'land', type: 'fill' as const, source: 'land', paint: { 'fill-color': p.land } },
                      { id: 'lakes', type: 'fill' as const, source: 'lakes', paint: { 'fill-color': p.water } },
                  ]),
            {
                id: 'boundaries',
                type: 'line',
                source: 'boundaries',
                paint: { 'line-color': p.boundary, 'line-width': 0.8 },
            },
            // Place labels fade in by importance (Natural Earth scalerank: 0 = biggest cities).
            ...[
                { id: 'places-major', maxRank: 2, minzoom: 1 },
                { id: 'places-mid', maxRank: 5, minzoom: 4 },
                { id: 'places-minor', maxRank: 99, minzoom: 6 },
            ].map((band) => ({
                id: band.id,
                type: 'symbol' as const,
                source: 'places',
                minzoom: band.minzoom,
                filter: ['<=', ['get', 'scalerank'], band.maxRank] as FilterSpecification,
                layout: {
                    'text-field': ['get', 'name'] as unknown as string,
                    'text-font': ['Noto Sans Regular'],
                    'text-size': 11,
                    'text-anchor': 'top' as const,
                    'text-offset': [0, 0.2] as [number, number],
                },
                paint: {
                    'text-color': p.placeText,
                    'text-halo-color': p.placeHalo,
                    'text-halo-width': 1,
                },
            })),
        ],
    };
}
