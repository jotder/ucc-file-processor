import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { Space, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpaceSwitcherComponent } from './space-switcher.component';

const SPACES: Space[] = [
    { id: 'alpha', displayName: 'Alpha', description: '', createdAt: '' },
    { id: 'beta', displayName: 'Beta', description: '', createdAt: '' },
];

function create(show: boolean) {
    const stub = {
        showSwitcher: signal(show),
        availableSpaces: signal(show ? SPACES : []),
        currentSpaceId: signal<string | null>(show ? 'alpha' : null),
        currentSpace: signal<Space | null>(show ? SPACES[0] : null),
        refresh: () => of(SPACES),
        selectSpace: () => {},
    } as unknown as SpacesService;
    TestBed.configureTestingModule({
        imports: [SpaceSwitcherComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: SpacesService, useValue: stub },
        ],
    });
    const fixture = TestBed.createComponent(SpaceSwitcherComponent);
    fixture.detectChanges();
    return fixture;
}

describe('SpaceSwitcherComponent', () => {
    it('renders nothing on a single-tenant server (no violations)', async () => {
        const fixture = create(false);
        expect(fixture.nativeElement.querySelector('button')).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders an accessible switcher when multiple spaces exist', async () => {
        const fixture = create(true);
        expect(fixture.nativeElement.querySelector('button[aria-label="Switch space"]')).not.toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
