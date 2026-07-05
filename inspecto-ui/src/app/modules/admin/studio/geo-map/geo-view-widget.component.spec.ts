import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ComponentDef, ComponentsService, GeoSettingsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DatasetsService } from '../datasets/datasets.service';
import { GeoViewWidgetComponent } from './geo-view-widget.component';

/** MapLibre never mounts in these paths (no data), so the host is jsdom-safe — same discipline as the
 *  studio spec: the pure projection logic is covered by geo-projection.spec. */
function create(components: Partial<ComponentsService>) {
    TestBed.configureTestingModule({
        imports: [GeoViewWidgetComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: components },
            { provide: GeoSettingsService, useValue: { get: () => of({ tileServerUrl: null }) } },
            { provide: DatasetsService, useValue: {} },
        ],
    });
    return TestBed.createComponent(GeoViewWidgetComponent);
}

const viewDef = (content: Record<string, unknown>): ComponentDef => ({
    type: 'geo-map-view',
    name: 'v1',
    ref: 'geo-map-view/v1',
    content,
});

describe('GeoViewWidgetComponent', () => {
    it('shows the unbound empty state (accessible) when no viewId is set', async () => {
        const fixture = create({});
        fixture.detectChanges();
        expect(fixture.componentInstance.loaded()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('No saved Geo view bound');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the empty state when the saved view does not exist (mock GET returns null)', () => {
        const fixture = create({ get: () => of(null as unknown as ComponentDef) });
        fixture.componentRef.setInput('viewId', 'missing');
        fixture.detectChanges();
        expect(fixture.componentInstance.view()).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('No saved Geo view bound');
    });

    it('surfaces an unknown geo source as an inline warning', () => {
        const fixture = create({ get: () => of(viewDef({ name: 'V', sourceId: 'bogus', query: {} })) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toContain('Unknown geo source');
        expect(fixture.nativeElement.textContent).toContain('Unknown geo source');
    });

    it('surfaces a failed view fetch as an inline warning', () => {
        const fixture = create({ get: () => throwError(() => new Error('down')) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toContain('Could not load the saved view');
    });
});
