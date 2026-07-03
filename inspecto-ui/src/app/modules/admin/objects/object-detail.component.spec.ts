import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { EventsService, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ObjectDetailComponent } from './object-detail.component';

const CASE: OperationalObject = {
    id: 'obj-9',
    objectType: 'CASE',
    title: 'Fraud ring',
    description: 'Suspicious voucher redemptions',
    status: 'INVESTIGATING',
    severity: 'CRITICAL',
    correlationId: 'corr-7',
    createdAt: 1,
    updatedAt: 2,
    closedAt: 0,
};

function create(overrides: Partial<Record<keyof ObjectsService, unknown>> = {}) {
    const api = {
        get: () => of(CASE),
        comments: vi.fn(() => of([])),
        addComment: vi.fn(() => of({})),
        transition: vi.fn(() => of({ ...CASE, status: 'ESCALATED' })),
        graph: () => of({ root: CASE.id, depth: 2, nodes: [], edges: [] }),
        attachments: () => of([]),
        applyRca: vi.fn(() => of([])),
        ...overrides,
    } as unknown as ObjectsService;
    TestBed.configureTestingModule({
        imports: [ObjectDetailComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ObjectsService, useValue: api },
            { provide: EventsService, useValue: { search: () => of([]) } },
            { provide: MatDialog, useValue: {} },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            // A real router (RouterLink needs createUrlTree); at the root URL listBase falls back to 'incidents'.
            provideRouter([]),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: new Map([['id', 'obj-9']]) } } },
        ],
    });
    const fixture = TestBed.createComponent(ObjectDetailComponent);
    fixture.detectChanges(); // ngOnInit → loadObject()
    return { fixture, api };
}

describe('ObjectDetailComponent', () => {
    it('loads the object and derives its legal transitions', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        expect(c.obj).toEqual(CASE);
        expect(c.actions).toEqual(['escalate', 'resolve']); // CASE @ INVESTIGATING
        expect(c.listBase).toBe('incidents'); // root test URL → fallback list base
        expect(c.listLabel).toBe('Incidents');
    });

    it('a transition replaces the object in place', () => {
        const { fixture, api } = create();
        const c = fixture.componentInstance;
        c.transition('escalate');
        expect(api.transition).toHaveBeenCalledWith('obj-9', 'escalate');
        expect(c.obj?.status).toBe('ESCALATED');
    });

    it('blocks an empty comment and submits a valid one', () => {
        const { fixture, api } = create();
        const c = fixture.componentInstance;
        c.addComment();
        expect(api.addComment).not.toHaveBeenCalled();
        c.commentForm.setValue({ body: 'triaged' });
        c.addComment();
        expect(api.addComment).toHaveBeenCalledWith('obj-9', 'triaged');
        expect(api.comments).toHaveBeenCalled();
    });

    it('degrades to not-found when the object load fails', () => {
        const { fixture } = create({ get: () => throwError(() => ({ status: 404 })) });
        expect(fixture.componentInstance.obj).toBeNull();
        expect(fixture.componentInstance.loading).toBe(false);
    });

    it('renders the overview with no a11y violations', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
