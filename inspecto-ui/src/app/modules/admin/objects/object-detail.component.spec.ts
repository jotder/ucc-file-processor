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

    it('builds the member timeline: CONTAINS members merged, comments newest-first, attributed', () => {
        const graph = {
            root: 'obj-9', depth: 1,
            nodes: [
                { id: 'obj-9', objectType: 'CASE', title: 'Fraud ring', status: 'INVESTIGATING' },
                { id: 'inc-1', objectType: 'INCIDENT', title: 'Redemption spike', status: 'OPEN' },
                { id: 'inc-2', objectType: 'INCIDENT', title: 'Geo anomaly', status: 'OPEN' },
                { id: 'other', objectType: 'ALERT', title: 'Unrelated', status: 'OPEN' },
            ],
            edges: [
                { from: 'obj-9', to: 'inc-1', relationship: 'CONTAINS' },
                { from: 'obj-9', to: 'inc-2', relationship: 'contains' },
                { from: 'obj-9', to: 'other', relationship: 'RELATED' },
            ],
        };
        const commentsById: Record<string, unknown[]> = {
            'inc-1': [{ id: 'c1', author: 'alice', body: 'first', createdAt: 100 }],
            'inc-2': [{ id: 'c2', author: 'bob', body: 'later', createdAt: 300 }],
        };
        const { fixture } = create({
            graph: () => of(graph),
            comments: vi.fn((mid: string) => of(commentsById[mid] ?? [])),
        });
        const c = fixture.componentInstance;
        c.loadMemberTimeline();
        // Only the two CONTAINS members (not the RELATED alert) contribute.
        expect(c.members.map((m) => m.id)).toEqual(['inc-1', 'inc-2']);
        expect(c.memberTimeline.map((t) => t.body)).toEqual(['later', 'first']); // newest-first
        expect(c.memberTimeline[0].memberTitle).toBe('Geo anomaly');
        expect(c.memberTimelineLoaded).toBe(true);
    });

    it('member timeline empty-states when the object contains nothing', () => {
        const { fixture } = create({
            graph: () => of({ root: 'obj-9', depth: 1, nodes: [{ id: 'obj-9', objectType: 'CASE', title: 'X', status: 'OPEN' }], edges: [] }),
        });
        const c = fixture.componentInstance;
        c.loadMemberTimeline();
        expect(c.members).toEqual([]);
        expect(c.memberTimeline).toEqual([]);
        expect(c.memberTimelineLoaded).toBe(true);
    });

    it('renders the overview with no a11y violations', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
