import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
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
    it('renders one heading, a menu item per option, and the common-panel prompt (no a11y violations)', async () => {
        const fixture = create();
        const el: HTMLElement = fixture.nativeElement;

        // Exactly one page <h1>.
        expect(el.querySelectorAll('h1').length).toBe(1);
        expect(el.querySelector('h1')?.textContent).toContain('Settings');

        // One menu item per configured option.
        const items = Array.from(el.querySelectorAll('nav[aria-label="Settings options"] button'));
        expect(items.length).toBe(fixture.componentInstance.drawers.length);

        // Nothing selected yet → common panel shows the empty-state prompt, no option component instantiated.
        expect(el.querySelector('inspecto-empty-state')).not.toBeNull();
        expect(el.querySelector('app-config')).toBeNull();

        await expectNoA11yViolations(el);
        fixture.destroy();
    });

    it('binds the selection to the :section route param and navigates on select (R5)', () => {
        const params = new BehaviorSubject(convertToParamMap({ section: 'spaces' }));
        TestBed.configureTestingModule({
            imports: [SettingsComponent],
            providers: [
                provideNoopAnimations(),
                provideRouter([]),
                { provide: ActivatedRoute, useValue: { paramMap: params } },
            ],
        });
        // No detectChanges — asserting the routing contract only (rendering a section would
        // instantiate that section's real component, which needs its own providers).
        const fixture = TestBed.createComponent(SettingsComponent);
        const c = fixture.componentInstance;
        expect(c.selected()?.id).toBe('spaces');

        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        c.select(c.drawers[0]);
        expect(navigate).toHaveBeenCalledWith(['/settings', 'config']);

        params.next(convertToParamMap({}));
        expect(c.selected()).toBeNull();
        fixture.destroy();
    });
});
