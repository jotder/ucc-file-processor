import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { WidgetsService } from './widgets.service';
import { DatasetsService } from '../datasets/datasets.service';
import { WidgetViewComponent } from './widget-view.component';

function create() {
    registerBuiltinViz();
    TestBed.configureTestingModule({
        imports: [WidgetViewComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: WidgetsService, useValue: { get: () => of(null) } },
            { provide: DatasetsService, useValue: { get: () => of(null) } },
        ],
    });
    const fixture = TestBed.createComponent(WidgetViewComponent);
    fixture.componentInstance.id = 'dur_by_tariff';
    return fixture;
}

describe('WidgetViewComponent', () => {
    it('shows the widget id in the breadcrumb and heading', () => {
        const fixture = create();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('dur_by_tariff');
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
