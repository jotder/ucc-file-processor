import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { AccessStateService } from 'app/inspecto/access/access-state.service';
import { AccessService } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { AccessComponent } from './access.component';

describe('AccessComponent', () => {
    async function create() {
        const api = {
            catalog: vi.fn(() => of({ version: 0, nodes: [] })),
            profiles: vi.fn(() => of([])),
            saveCatalog: vi.fn((_c: unknown) => of({ version: 1, nodes: [] })),
            saveProfile: vi.fn((_p: unknown) => of({})),
        };
        const accessState = { reload: vi.fn() };
        const toastr = { success: vi.fn(), error: vi.fn() };
        TestBed.configureTestingModule({
            imports: [AccessComponent],
            providers: [
                provideNoopAnimations(),
                { provide: AccessService, useValue: api },
                { provide: AccessStateService, useValue: accessState },
                { provide: ToastrService, useValue: toastr },
                { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            ],
        });
        await TestBed.compileComponents();
        const fixture = TestBed.createComponent(AccessComponent);
        fixture.detectChanges();
        return { fixture, c: fixture.componentInstance, api, accessState, toastr };
    }

    it('derives the matrix from the platform nav and starts clean (all Shown)', async () => {
        const { c } = await create();
        const roots = c.nodes().map((n) => n.id);
        expect(roots).toContain('business-group');
        expect(roots).toContain('platform-group');
        expect(c.dirty()).toBe(false);
        expect(c.hiddenCounts()['business']).toBe(0);
    });

    it('cycles a cell Inherit → Hidden → Shown → Inherit, with inheritance flowing to children', async () => {
        const { c } = await create();
        c.cycle('workbench-group', 'business');
        expect(c.dirty()).toBe(true);
        expect(c.hiddenCounts()['business']).toBeGreaterThan(1); // the group + its subtree
        // a child inherits the deny and names its source
        const child = c['cellState']('pipelines', 'business');
        expect(child.effective).toBe('deny');
        expect(child.explicit).toBeNull();
        expect(child.sourceLabel).toBe('Workbench');
        c.cycle('workbench-group', 'business'); // → explicit allow
        expect(c['cellState']('workbench-group', 'business').explicit).toBe('allow');
        c.cycle('workbench-group', 'business'); // → back to inherit
        expect(c.dirty()).toBe(false);
    });

    it('save snapshots the catalog + only the dirty lens profiles, then applies live', async () => {
        const { c, api, accessState, toastr } = await create();
        c.cycle('system-maintenance-group', 'business');
        c.save();
        expect(api.saveCatalog).toHaveBeenCalledTimes(1);
        expect((api.saveCatalog.mock.calls[0][0] as { version: number }).version).toBe(1);
        expect(api.saveProfile).toHaveBeenCalledTimes(1); // only lens-business changed
        expect(api.saveProfile.mock.calls[0][0]).toMatchObject({
            subjectType: 'lens',
            subjectId: 'business',
            grants: { 'system-maintenance-group': 'deny' },
        });
        expect(c.dirty()).toBe(false);
        expect(accessState.reload).toHaveBeenCalled();
        expect(toastr.success).toHaveBeenCalled();
    });

    it('search filters the tree keeping ancestors of matches', async () => {
        const { c } = await create();
        c.onSearch('Pipelines');
        const roots = c.nodes().map((n) => n.id);
        expect(roots).toEqual(['platform-group']);
        expect(c.nodes()[0].children!.map((n) => n.id)).toEqual(['workbench-group']);
    });

    it('has no a11y violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
