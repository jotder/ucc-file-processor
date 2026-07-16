import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi, type Mock } from 'vitest';
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
        tags: vi.fn(() => of([{ name: 'billing', createdAt: 1 }])),
        tagRules: vi.fn(() => of([])),
        createTag: vi.fn((name: string) => of({ name, createdAt: 2 })),
    } as unknown as ObjectsService;
    TestBed.configureTestingModule({
        imports: [ObjectMailComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ObjectsService, useValue: api },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            provideRouter([]),
            {
                provide: ActivatedRoute,
                useValue: {
                    snapshot: { data: { type: 'INCIDENT', title: 'Incidents', subtitle: '' } },
                    queryParamMap: of(convertToParamMap({})),
                },
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

    it('merges registry tags (zero-count) with tags derived from the rows, and filters by tag', async () => {
        const { c } = await create();
        expect(c.tags()).toEqual([
            { tag: 'network', count: 1 },
            { tag: 'urgent', count: 1 },
            { tag: 'billing', count: 0 }, // registry-only tag stays visible
        ]);
        c.selectTag('urgent');
        expect(c.rows().map((o) => o.id)).toEqual(['i2']);
        c.selectFolder('resolved');
        expect(c.tagFilter()).toBeNull();
        expect(c.rows().map((o) => o.id)).toEqual(['i3']);
    });

    it('creates a tag from the nav inline input and refreshes the registry', async () => {
        const { c, api } = await create();
        c.navNewTag.setValue('  feeds ');
        c.createNavTag();
        expect(api.createTag).toHaveBeenCalledWith('feeds');
        expect(api.tags).toHaveBeenCalledTimes(2); // init + refresh
        expect(c.navNewTag.value).toBe('');
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

    it('triage verbs are optimistic: rows reconcile in place, no list refetch, selection clears', async () => {
        const { c, api } = await create();
        // The server echoes the updated object — reconcile must adopt it (updatedAt stamp).
        (api.update as unknown as Mock).mockImplementation((id: string, patch: { priority?: string }) =>
            of({ ...OBJECTS.find((o) => o.id === id)!, priority: patch.priority, updatedAt: 99 }),
        );
        c.onSelection([OBJECTS[0]] as unknown as Record<string, unknown>[]);
        c.prioritize('MAJOR');
        const row = c.objects().find((o) => o.id === 'i1')!;
        expect(row.priority).toBe('MAJOR');
        expect(row.updatedAt).toBe(99); // server truth, not just the optimistic patch
        expect(api.list).toHaveBeenCalledTimes(1); // R4: success never triggers a full refetch
        expect(c.selected()).toEqual([]); // old reload() contract preserved
    });

    it('a failed triage verb reloads from the server (partial success ⇒ only a refetch is honest)', async () => {
        const { c, api } = await create();
        (api.update as unknown as Mock).mockReturnValue(throwError(() => new Error('boom')));
        c.onSelection([OBJECTS[0]] as unknown as Record<string, unknown>[]);
        c.prioritize('MAJOR');
        expect(api.list).toHaveBeenCalledTimes(2); // initial load + error-path reload
    });

    it('renders the 3-pane shell with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('offers Load more once a fetch returns a full page, and widens the limit on click (R6a)', async () => {
        const fullPage = Array.from({ length: 500 }, (_, i) => incident(`p${i}`, 'IDENTIFIED'));
        const api = {
            list: vi.fn(() => of(fullPage)),
            tags: vi.fn(() => of([])),
            tagRules: vi.fn(() => of([])),
        } as unknown as ObjectsService;
        TestBed.configureTestingModule({
            imports: [ObjectMailComponent],
            providers: [
                provideNoopAnimations(),
                { provide: ObjectsService, useValue: api },
                { provide: MatDialog, useValue: {} },
                { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
                { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
                provideRouter([]),
                {
                    provide: ActivatedRoute,
                    useValue: {
                        snapshot: { data: { type: 'INCIDENT', title: 'Incidents', subtitle: '' } },
                        queryParamMap: of(convertToParamMap({})),
                    },
                },
                { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
                { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            ],
        });
        await TestBed.compileComponents();
        const fixture = TestBed.createComponent(ObjectMailComponent);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.hasMore()).toBe(true);
        c.loadMore();
        expect((api.list as unknown as Mock).mock.calls[1][0]).toMatchObject({ limit: 1000 });
    });
});
