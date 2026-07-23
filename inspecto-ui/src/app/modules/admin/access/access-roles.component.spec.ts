import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';

import { deriveDefaultAccessCatalog } from 'app/inspecto/access/access-catalog';
import { AccessNode, AccessService, LensService, RoleDef } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AccessRolesComponent } from './access-roles.component';

/** First catalog action node — a real (capability, nodeId) pair for the profile-deny projection. */
function firstAction(): AccessNode {
    const walk = (ns: AccessNode[]): AccessNode | null => {
        for (const n of ns) {
            if (n.kind === 'action' && n.capability) return n;
            const hit = n.children?.length ? walk(n.children) : null;
            if (hit) return hit;
        }
        return null;
    };
    const found = walk(deriveDefaultAccessCatalog());
    if (!found) throw new Error('derived catalog has no action nodes');
    return found;
}

const ACTION = firstAction();

const SEED_OPS: RoleDef = { name: 'operations', capabilities: ['canOperateRuns', 'canRequestShares'], source: 'seed' };
const AUTHORED: RoleDef = {
    name: 'fraud-analyst',
    capabilities: [ACTION.capability!, 'canOperateRuns'],
    dataScopes: ['fraud'],
    source: 'authored',
};

function create(opts: {
    roles?: RoleDef[];
    error?: string;
    profiles?: unknown[];
    canEdit?: boolean;
    dialogResult?: RoleDef;
} = {}) {
    const saveRoles = vi.fn((authored: RoleDef[]) =>
        of({ roles: authored.map((r) => ({ ...r, source: 'authored' as const })) }));
    const api = {
        roles: vi.fn(() => of({ roles: opts.roles ?? [SEED_OPS, AUTHORED], error: opts.error })),
        profiles: vi.fn(() => of(opts.profiles ?? [])),
        saveRoles,
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [AccessRolesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AccessService, useValue: api },
            { provide: LensService, useValue: { canConfigureAccess: () => opts.canEdit ?? true } },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(opts.dialogResult) }) } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(() => Promise.resolve(true)) } },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    const fixture = TestBed.createComponent(AccessRolesComponent);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, saveRoles, toastr };
}

describe('AccessRolesComponent', () => {
    it('renders one card per role with its source and capability chips', () => {
        const { fixture, c } = create();
        expect(c.cards().map((card) => card.role.name)).toEqual(['operations', 'fraud-analyst']);
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Seed default');
        expect(text).toContain('Authored');
        expect(text).toContain('canOperateRuns');
        expect(text).toContain('Data scopes: fraud');
    });

    it('offers the full vocabulary: catalog-bound capabilities plus everything any role grants', () => {
        const { c } = create({
            roles: [{ name: 'super', capabilities: ['canApproveShares'], source: 'seed' }],
        });
        expect(c.vocabulary()).toContain(ACTION.capability!);   // from the derived catalog
        expect(c.vocabulary()).toContain('canApproveShares');   // from a role row only
    });

    it('strikes a capability the role\'s own Access Profile denies (effective view, R2 semantics)', () => {
        const { c } = create({
            profiles: [{
                subjectType: 'role', subjectId: 'fraud-analyst', label: 'fraud-analyst',
                grants: { [ACTION.id]: 'deny' },
            }],
        });
        const fraud = c.cards().find((card) => card.role.name === 'fraud-analyst')!;
        const denied = fraud.capabilities.find((cap) => cap.capability === ACTION.capability)!;
        expect(denied.deniedByProfile).toBe(true);
        expect(fraud.capabilities.find((cap) => cap.capability === 'canOperateRuns')!.deniedByProfile).toBe(false);
        // the seed role has no profile — nothing struck
        const ops = c.cards().find((card) => card.role.name === 'operations')!;
        expect(ops.capabilities.every((cap) => !cap.deniedByProfile)).toBe(true);
    });

    it('revert PUTs the authored overlay without the role (seed fallback / custom removal)', async () => {
        const { c, saveRoles } = create();
        await c.revert(AUTHORED);
        expect(saveRoles).toHaveBeenCalledTimes(1);
        expect(saveRoles.mock.calls[0][0]).toEqual([]);   // fraud-analyst was the only authored row
    });

    it('a dialog save replaces the role inside the authored overlay and reloads from the response', () => {
        const edited: RoleDef = { name: 'operations', capabilities: ['canOperateRuns'] };
        const { c, saveRoles, toastr } = create({ dialogResult: edited });
        c.edit(SEED_OPS);
        expect(saveRoles).toHaveBeenCalledTimes(1);
        // editing a seed role moves it into the overlay, beside the already-authored row
        expect(saveRoles.mock.calls[0][0]).toEqual([
            { name: AUTHORED.name, capabilities: AUTHORED.capabilities, dataScopes: ['fraud'], source: 'authored' },
            edited,
        ]);
        expect(c.rows().every((r) => r.source === 'authored')).toBe(true);   // reloaded from the PUT response
        expect(toastr.success).toHaveBeenCalled();
    });

    it('is read-only without the access-configuration capability', () => {
        const { fixture, c, saveRoles } = create({ canEdit: false, dialogResult: SEED_OPS });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Read-only');
        expect(fixture.nativeElement.querySelector('button')).toBeNull();   // no New role / edit / revert
        c.edit(SEED_OPS);   // defense in depth: the mutating method itself is gated
        expect(saveRoles).not.toHaveBeenCalled();
    });

    it('surfaces the fail-closed unreadable-doc error', () => {
        const { fixture } = create({ roles: [], error: 'roles.toon is unreadable — all role grants are suspended' });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Role grants suspended');
        expect(text).toContain('roles.toon is unreadable');
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
