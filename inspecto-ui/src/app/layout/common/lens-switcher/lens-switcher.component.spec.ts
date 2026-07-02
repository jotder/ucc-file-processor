import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { beforeEach, describe, expect, it } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LensSwitcherComponent } from './lens-switcher.component';

function create() {
    TestBed.configureTestingModule({
        imports: [LensSwitcherComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(LensSwitcherComponent);
    fixture.detectChanges();
    return fixture;
}

describe('LensSwitcherComponent', () => {
    beforeEach(() => localStorage.clear());

    it('shows the current lens label (default Builder)', () => {
        const fixture = create();
        expect(fixture.componentInstance.labelFor(fixture.componentInstance.lens.currentLens())).toBe('Builder');
    });

    it('selecting a lens updates the shared LensService', () => {
        const fixture = create();
        const lens = TestBed.inject(LensService);
        fixture.componentInstance.lens.selectLens('business');
        expect(lens.currentLens()).toBe('business');
        expect(lens.readOnly()).toBe(true);
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
