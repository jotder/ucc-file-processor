import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ObjectsComponent } from './objects.component';

const INCIDENT: OperationalObject = {
    id: 'obj-1',
    objectType: 'INCIDENT',
    title: 'Late feed',
    description: '',
    status: 'OPEN',
    severity: 'WARNING',
    createdAt: 1,
    updatedAt: 1,
    closedAt: 0,
};

async function create() {
    const router = { navigate: vi.fn(), url: '/incidents' };
    const api = {
        list: vi.fn(() => of([INCIDENT])),
        transition: vi.fn(() => of({ ...INCIDENT, status: 'ASSIGNED' })),
    } as unknown as ObjectsService;
    TestBed.configureTestingModule({
        imports: [ObjectsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ObjectsService, useValue: api },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            { provide: Router, useValue: router },
            {
                provide: ActivatedRoute,
                useValue: { snapshot: { data: { type: 'INCIDENT', title: 'Incidents', subtitle: '' } } },
            },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(ObjectsComponent);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api, router };
}

describe('ObjectsComponent', () => {
    it('loads the route-typed objects on init', async () => {
        const { fixture, api } = await create();
        expect(api.list).toHaveBeenCalledWith({ type: 'INCIDENT', limit: 500 });
        expect(fixture.componentInstance.objects).toEqual([INCIDENT]);
    });

    it('derives the happy-path next action from status and type', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.nextAction(INCIDENT)).toBe('assign');
        expect(c.nextAction({ ...INCIDENT, status: 'RESOLVED' })).toBe('close');
        expect(c.nextAction({ ...INCIDENT, status: 'CLOSED' })).toBeUndefined();
        expect(c.nextAction({ ...INCIDENT, objectType: 'CASE', status: 'INVESTIGATING' })).toBe('escalate');
    });

    it('advances the lifecycle after confirm and reloads', async () => {
        const { fixture, api } = await create();
        await fixture.componentInstance.advance(INCIDENT);
        expect(api.transition).toHaveBeenCalledWith('obj-1', 'assign');
        expect(api.list).toHaveBeenCalledTimes(2);
    });

    it('row click navigates to the detail route', async () => {
        const { fixture, router } = await create();
        fixture.componentInstance.onRowClicked(INCIDENT);
        expect(router.navigate).toHaveBeenCalledWith(['/incidents', 'obj-1']);
    });

    it('renders the loaded grid with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
