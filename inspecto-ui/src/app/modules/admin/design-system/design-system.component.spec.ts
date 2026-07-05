import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { GammaConfigService } from '@gamma/services/config';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DesignSystemComponent } from './design-system.component';

async function create() {
    TestBed.configureTestingModule({
        imports: [DesignSystemComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined, warning: () => undefined, info: () => undefined } },
            // the embedded map host tracks the colour scheme
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // the embedded data-table has a @defer block
    const fixture = TestBed.createComponent(DesignSystemComponent);
    fixture.detectChanges();
    return fixture;
}

describe('DesignSystemComponent', () => {
    it('renders the gallery: heading plus one section per shared pattern', async () => {
        const fixture = await create();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('h1')?.textContent).toContain('Design System');
        const sections = Array.from(el.querySelectorAll('section h2')).map((h) => h.textContent?.trim());
        expect(sections).toContain('Status badge');
        expect(sections).toContain('Data grid');
        expect(sections).toContain('Data table');
    });

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
