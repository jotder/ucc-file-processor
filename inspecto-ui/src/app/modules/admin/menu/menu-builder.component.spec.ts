import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { NavigationService } from 'app/core/navigation/navigation.service';
import { SpacesService } from 'app/inspecto/api';
import { NavMenusService, emptyTree } from 'app/inspecto/menu';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MenuBuilderComponent } from './menu-builder.component';

describe('MenuBuilderComponent', () => {
    beforeEach(() => localStorage.clear());

    it('renders the empty state with no menus, titled Menus, and is a11y-clean', async () => {
        TestBed.configureTestingModule({
            imports: [MenuBuilderComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SpacesService, useValue: { currentSpaceId: signal<string | null>(null) } },
                { provide: NavigationService, useValue: { get: () => of(null) } },
                { provide: NavMenusService, useValue: { get: () => of(emptyTree('default')), put: () => of(emptyTree('default')) } },
                { provide: ToastrService, useValue: { error: () => {} } },
                { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            ],
        });
        const f = TestBed.createComponent(MenuBuilderComponent);
        f.detectChanges();
        expect(f.nativeElement.querySelector('h1')?.textContent).toContain('Menus');
        expect(f.nativeElement.textContent).toContain('No menus yet');
        await expectNoA11yViolations(f.nativeElement);
    });
});
