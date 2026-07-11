import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ObjectMailComponent } from './object-mail.component';

function incident(id: string, status: string, extra: Partial<OperationalObject> = {}): OperationalObject {
    return {
        id,
        objectType: 'INCIDENT',
        title: `Incident ${id}`,
        description: 'sample',
        status,
        createdAt: 1,
        updatedAt: 1,
        closedAt: 0,
        ...extra,
    };
}

const OBJECTS: OperationalObject[] = [
    incident('i1', 'IDENTIFIED'),
    incident('i2', 'DIAGNOSING', { assignee: 'operator', attributes: { tags: 'urgent,network', escalated: 'true' } }),
    incident('i3', 'RESOLVED', { attributes: { category: 'Pipeline / Ingest / Parse failure' } }),
    incident('i4', 'OPEN'), // legacy status → normalizes to IDENTIFIED
    incident('i5', 'ARCHIVED'),
];

async function create() {
    const api = {
        list: vi.fn(() => of(OBJECTS)),
        update: vi.fn((id: string) => of(OBJECTS.find((o) => o.id === id))),
        transition: vi.fn((id: string) => of(OBJECTS.find((o) => o.id === id))),
        addComment: vi.fn(() => of({})),
    } as unknown as ObjectsService;
    TestBed.configureTestingModule({
        imports: [ObjectMailComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ObjectsService, useValue: api },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            {
                provide: ActivatedRoute,
                useValue: { snapshot: { data: { type: 'INCIDENT', title: 'Incidents', subtitle: '' } } },
            },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(ObjectMailComponent);
    fixture.detectChanges(); // ngOnInit → reload()
    return { fixture, c: fixture.componentInstance, api };
}

describe('ObjectMailComponent', () => {
    it('folders the loaded incidents on the normalized mail lifecycle', async () => {
        const { c } = await create();
        const counts = c.counts();
        expect(counts.get('identified')).toBe(2); // i1 + legacy OPEN i4
        expect(counts.get('diagnosing')).toBe(1);
        expect(counts.get('resolved')).toBe(1);
        expect(counts.get('archived')).toBe(1);
        expect(counts.get('mine')).toBe(1); // i2 assigned to 'operator'
        expect(counts.get('escalated')).toBe(1); // i2 flag
        // Default folder is the Inbox (Identified).
        expect(c.rows().map((o) => o.id).sort()).toEqual(['i1', 'i4']);
    });

    it('derives the tag list from attributes and filters rows by tag', async () => {
        const { c } = await create();
        expect(c.tags()).toEqual([
            { tag: 'network', count: 1 },
            { tag: 'urgent', count: 1 },
        ]);
        c.selectTag('urgent');
        expect(c.rows().map((o) => o.id)).toEqual(['i2']);
        c.selectFolder('resolved');
        expect(c.tagFilter()).toBeNull();
        expect(c.rows().map((o) => o.id)).toEqual(['i3']);
    });

    it('enables toolbar actions from the selection lifecycle', async () => {
        const { c } = await create();
        expect(c.canAccept()).toBe(false);
        c.onSelection([OBJECTS[0] as unknown as Record<string, unknown>]); // IDENTIFIED
        expect(c.canAccept()).toBe(true);
        expect(c.canResolve()).toBe(true);
        expect(c.canReopen()).toBe(false);
        c.onSelection([OBJECTS[2] as unknown as Record<string, unknown>]); // RESOLVED
        expect(c.canAccept()).toBe(false);
        expect(c.canReopen()).toBe(true);
    });

    it('accept assigns me + transitions when the category is already set', async () => {
        const { c, api } = await create();
        const categorized = incident('i9', 'IDENTIFIED', { attributes: { category: 'Security / Access / Expired credentials' } });
        c.accept([categorized]);
        expect(api.update).toHaveBeenCalledWith('i9', { assignee: 'operator' });
        expect(api.transition).toHaveBeenCalledWith('i9', 'accept', 'operator');
    });

    it('prioritize patches every selected object', async () => {
        const { c, api } = await create();
        c.onSelection([OBJECTS[0], OBJECTS[1]] as unknown as Record<string, unknown>[]);
        c.prioritize('MAJOR');
        expect(api.update).toHaveBeenCalledWith('i1', { priority: 'MAJOR' });
        expect(api.update).toHaveBeenCalledWith('i2', { priority: 'MAJOR' });
    });

    it('escalate toggles the escalated attribute (de-escalates when all selected are escalated)', async () => {
        const { c, api } = await create();
        c.onSelection([OBJECTS[1]] as unknown as Record<string, unknown>[]); // escalated
        expect(c.escalateLabel()).toBe('De-escalate');
        c.escalate();
        expect(api.update).toHaveBeenCalledWith('i2', { attributes: { escalated: 'false' } });
    });

    it('renders the 3-pane shell with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
