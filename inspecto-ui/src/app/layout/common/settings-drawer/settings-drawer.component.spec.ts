import { TestBed } from '@angular/core/testing';
import { OverlayContainer } from '@angular/cdk/overlay';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SettingsDrawerComponent } from './settings-drawer.component';

function create() {
    TestBed.configureTestingModule({
        imports: [SettingsDrawerComponent],
        providers: [provideNoopAnimations(), provideRouter([])],
    });
    const fixture = TestBed.createComponent(SettingsDrawerComponent);
    fixture.detectChanges();
    return fixture;
}

describe('SettingsDrawerComponent', () => {
    it('renders a settings cog and no drawer until opened (no a11y violations closed)', async () => {
        const fixture = create();
        const overlay = TestBed.inject(OverlayContainer).getContainerElement();
        expect(fixture.nativeElement.querySelector('button[aria-label="Open settings"]')).toBeTruthy();
        expect(overlay.querySelector('[role="dialog"]')).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
        fixture.destroy();
    });

    it('opens a focus-trapped drawer listing every settings link, then closes', async () => {
        const fixture = create();
        const overlay = TestBed.inject(OverlayContainer).getContainerElement();

        fixture.componentInstance.openDrawer();
        fixture.detectChanges();

        const dialog = overlay.querySelector('[role="dialog"][aria-label="Settings"]');
        expect(dialog).toBeTruthy();
        const links = Array.from(overlay.querySelectorAll('a[href]'));
        expect(links.length).toBe(fixture.componentInstance.items.length);
        expect(links.some((a) => a.getAttribute('href') === '/config')).toBe(true);
        expect(links.some((a) => a.getAttribute('href') === '/settings/menus')).toBe(true);
        await expectNoA11yViolations(overlay);

        fixture.componentInstance.close();
        fixture.detectChanges();
        expect(overlay.querySelector('[role="dialog"]')).toBeNull();
        fixture.destroy();
    });
});
