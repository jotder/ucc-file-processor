import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MapViewComponent } from './map-view.component';
import { GeoData } from './geo-types';

const CONFIG_PROVIDER = { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } };

const DATA: GeoData = {
    points: [
        { id: 'a', lat: 23.8, lon: 90.4, kind: 'tower', label: 'A' },
        { id: 'b', lat: 51.5, lon: -0.13, kind: 'device', label: 'B' },
    ],
    routes: [],
};

// jsdom has no WebGL, so the MapLibre map never mounts (the guarded path) — these specs cover
// the component shell; the live map is verified in the browser (/design gallery).
describe('MapViewComponent', () => {
    it('stays unmounted without data and passes axe', async () => {
        await TestBed.configureTestingModule({ imports: [MapViewComponent], providers: [CONFIG_PROVIDER] }).compileComponents();
        const fixture = TestBed.createComponent(MapViewComponent);
        fixture.componentInstance.data = null;
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('tolerates data + input changes where WebGL is unavailable', async () => {
        await TestBed.configureTestingModule({ imports: [MapViewComponent], providers: [CONFIG_PROVIDER] }).compileComponents();
        const fixture = TestBed.createComponent(MapViewComponent);
        fixture.componentInstance.data = DATA;
        fixture.detectChanges();
        // guarded mount: no canvas, no throw; export/fit are safe no-ops
        expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
        expect(fixture.componentInstance.exportPng()).toBeNull();
        fixture.componentInstance.fitToData();
        fixture.componentInstance.data = { points: [], routes: [] };
        fixture.componentInstance.ngOnChanges();
        expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
    });
});
