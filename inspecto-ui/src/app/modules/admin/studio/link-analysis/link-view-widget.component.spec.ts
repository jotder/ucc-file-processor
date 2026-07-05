import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { CatalogService, ComponentDef, ComponentsService, PipelinesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentsDataProvider } from 'app/modules/admin/catalog/components-data-provider';
import { DatasetsService } from '../datasets/datasets.service';
import { LinkViewWidgetComponent } from './link-view-widget.component';

/** G6 never mounts in these paths (no data), so the host is jsdom-safe — the source query contracts are
 *  covered by graph-sources.spec. */
function create(components: Partial<ComponentsService>) {
    TestBed.configureTestingModule({
        imports: [LinkViewWidgetComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: components },
            { provide: CatalogService, useValue: {} },
            { provide: PipelinesService, useValue: {} },
            { provide: ComponentsDataProvider, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ],
    });
    return TestBed.createComponent(LinkViewWidgetComponent);
}

const viewDef = (content: Record<string, unknown>): ComponentDef => ({
    type: 'link-analysis-view',
    name: 'v1',
    ref: 'link-analysis-view/v1',
    content,
});

describe('LinkViewWidgetComponent', () => {
    it('shows the unbound empty state (accessible) when no viewId is set', async () => {
        const fixture = create({});
        fixture.detectChanges();
        expect(fixture.componentInstance.loaded()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('No saved Link-Analysis view bound');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the empty state when the saved view does not exist (mock GET returns null)', () => {
        const fixture = create({ get: () => of(null as unknown as ComponentDef) });
        fixture.componentRef.setInput('viewId', 'missing');
        fixture.detectChanges();
        expect(fixture.componentInstance.view()).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('No saved Link-Analysis view bound');
    });

    it('surfaces an unknown graph source as an inline warning', () => {
        const fixture = create({ get: () => of(viewDef({ name: 'V', sourceId: 'bogus', query: {} })) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toContain('Unknown graph source');
    });

    it('surfaces a failed view fetch as an inline warning', () => {
        const fixture = create({ get: () => throwError(() => new Error('down')) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toContain('Could not load the saved view');
    });
});
