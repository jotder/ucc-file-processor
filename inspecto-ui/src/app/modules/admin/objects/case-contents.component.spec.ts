import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ObjectGraph, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { CaseContentsComponent, MemberRollup } from './case-contents.component';

const CASE: OperationalObject = {
    id: 'case-1',
    objectType: 'CASE',
    title: 'investigation',
    description: '',
    status: 'INVESTIGATING',
    createdAt: 1,
    updatedAt: 1,
    closedAt: 0,
};

const GRAPH: ObjectGraph = {
    root: 'case-1',
    depth: 1,
    nodes: [
        { id: 'case-1', objectType: 'CASE', title: 'investigation', status: 'INVESTIGATING' },
        { id: 'i1', objectType: 'INCIDENT', title: 'late feed', status: 'DIAGNOSING' },
        { id: 'i2', objectType: 'INCIDENT', title: 'old glitch', status: 'CLOSED' }, // legacy → ARCHIVED
        { id: 'other', objectType: 'ALERT', title: 'unrelated neighbour', status: 'OPEN' },
    ],
    edges: [
        { from: 'case-1', fromType: 'CASE', to: 'i1', toType: 'INCIDENT', relationship: 'CONTAINS', createdAt: 1 },
        { from: 'case-1', fromType: 'CASE', to: 'i2', toType: 'INCIDENT', relationship: 'CONTAINS', createdAt: 1 },
        { from: 'other', fromType: 'ALERT', to: 'case-1', toType: 'CASE', relationship: 'RELATED_TO', createdAt: 1 },
    ],
};

function create() {
    const api = {
        graph: vi.fn(() => of(GRAPH)),
        unlink: vi.fn(() => of({ deleted: true })),
    } as unknown as ObjectsService;
    TestBed.configureTestingModule({
        imports: [CaseContentsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ObjectsService, useValue: api },
            { provide: MatDialog, useValue: {} },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(CaseContentsComponent);
    fixture.componentRef.setInput('object', CASE);
    const rollups: MemberRollup[] = [];
    fixture.componentInstance.membersChange.subscribe((r) => rollups.push(r));
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, api, rollups };
}

describe('CaseContentsComponent', () => {
    it('lists only the CONTAINS members and emits the roll-up', () => {
        const { c, api, rollups } = create();
        expect(api.graph).toHaveBeenCalledWith('case-1', 1);
        expect(c.members().map((n) => n.id)).toEqual(['i1', 'i2']); // 'other' is a neighbour, not a member
        expect(c.openCount()).toBe(1); // i2's legacy CLOSED folds to ARCHIVED → not open
        expect(rollups.at(-1)).toEqual({ total: 2, open: 1 });
    });

    it('removing a member unlinks the CONTAINS edge and reloads', () => {
        const { c, api } = create();
        c.removeMember(c.members()[0]);
        expect(api.unlink).toHaveBeenCalledWith('case-1', 'i1', 'CONTAINS', expect.any(String));
        expect(api.graph).toHaveBeenCalledTimes(2); // init + reload
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
