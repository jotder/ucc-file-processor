import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { Space, SpacesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpacesComponent } from './spaces.component';

const SPACES: Space[] = [
    { id: 'alpha', displayName: 'Alpha', description: 'first project', createdAt: '2026-06-23 10:00:00' },
    { id: 'beta', displayName: '', description: '', createdAt: '' },
];

function create(multi: boolean, list: Space[]) {
    const stub = {
        multiSpace: signal(multi),
        availableSpaces: signal(list),
        currentSpaceId: signal<string | null>(multi ? 'alpha' : null),
        refresh: () => of(list),
        selectSpace: () => {},
        dataSources: () => of([]),
    } as unknown as SpacesService;
    TestBed.configureTestingModule({
        imports: [SpacesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: SpacesService, useValue: stub },
            { provide: MatDialog, useValue: {} },
            { provide: ToastrService, useValue: {} },
            { provide: InspectoConfirmService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(SpacesComponent);
    fixture.detectChanges();
    return fixture;
}

describe('SpacesComponent', () => {
    it('lists spaces with no a11y violations', async () => {
        const fixture = create(true, SPACES);
        expect(fixture.componentInstance.spaces.availableSpaces().length).toBe(2);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows single-tenant guidance with no a11y violations', async () => {
        const fixture = create(false, []);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
