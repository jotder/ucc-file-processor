import { describe, expect, it } from 'vitest';
import { basemapStyle } from './map-tokens';

describe('basemapStyle (tile-server seam)', () => {
    it('renders the offline land/lake fills by default (no imagery source)', () => {
        const style = basemapStyle(false, 'http://app/assets/basemap');
        expect(Object.keys(style.sources)).toEqual(['land', 'lakes', 'boundaries', 'places']);
        expect(style.layers.map((l) => l.id)).toContain('land');
        expect(style.layers.map((l) => l.id)).not.toContain('imagery');
    });

    it('swaps land/lake fills for a customer raster layer when a tile server is set, keeping labels on top', () => {
        const style = basemapStyle(false, 'http://app/assets/basemap', 'https://t.example/{z}/{x}/{y}.png');
        expect(style.sources['imagery']).toEqual(
            expect.objectContaining({ type: 'raster', tiles: ['https://t.example/{z}/{x}/{y}.png'] }),
        );
        const ids = style.layers.map((l) => l.id);
        expect(ids).toContain('imagery');
        expect(ids).not.toContain('land');
        // boundaries + place labels still draw above the imagery
        expect(ids.indexOf('imagery')).toBeLessThan(ids.indexOf('boundaries'));
        expect(ids).toContain('places-major');
    });
});
