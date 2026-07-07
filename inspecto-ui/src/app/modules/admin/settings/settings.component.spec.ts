import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SettingsComponent } from './settings.component';

function create() {
    TestBed.configureTestingModule({
        imports: [SettingsComponent],
        providers: [provideNoopAnimations(), provideRouter([])],
    });
    const fixture = TestBed.createComponent(SettingsComponent);
    fixture.detectChanges();
    return fixture;
}

describe('SettingsComponent', () => {
    it('renders one heading and a drawer per settings option (collapsed, no a11y violations)', async () => {
        const fixture = create();
        const el: HTMLElement = fixture.nativeElement;

        // Exactly one page <h1>.
        expect(el.querySelectorAll('h1').length).toBe(1);
        expect(el.querySelector('h1')?.textContent).toContain('Settings');

        // One drawer (expansion panel) per configured option.
        const panels = Array.from(el.querySelectorAll('mat-expansion-panel'));
        expect(panels.length).toBe(fixture.componentInstance.drawers.length);

        // Drawers start collapsed → no embedded option component is instantiated yet.
        expect(el.querySelector('app-config')).toBeNull();

        await expectNoA11yViolations(el);
        fixture.destroy();
    });
});
