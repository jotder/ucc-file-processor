import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { LensService, SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LensSwitcherComponent } from './lens-switcher.component';

function create() {
    TestBed.configureTestingModule({
        imports: [LensSwitcherComponent],
        // LensService pulls SessionService (the R2 grant source) → HTTP/router DI
        providers: [provideNoopAnimations(), provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
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

    it('offers only the lenses the subject grants project onto (R2)', () => {
        const fixture = create();
        const session = TestBed.inject(SessionService);
        session.authMode.set('oidc');
        session.capabilities.set(['canOperateRuns']); // operations-only subject
        fixture.detectChanges();
        expect(fixture.componentInstance.lens.allowedLenses().map((l) => l.id)).toEqual(['business', 'ops']);
        expect(fixture.componentInstance.lens.currentLens()).toBe('ops');
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
