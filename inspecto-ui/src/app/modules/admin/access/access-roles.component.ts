import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import {
    CatalogIndex,
    deriveDefaultAccessCatalog,
    indexCatalog,
    resolveGrant,
} from 'app/inspecto/access/access-catalog';
import {
    AccessNode,
    AccessProfile,
    AccessService,
    apiErrorMessage,
    LensService,
    RoleDef,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { RoleFormDialog, RoleFormData } from './role-form.dialog';

/** One capability chip on a role card: granted by the table, possibly denied by the role's profile. */
interface CapabilityState {
    capability: string;
    deniedByProfile: boolean;
}

/**
 * Settings ▸ Access ▸ Roles (RBAC R5): the authorable role → capability/data-scope table behind
 * every OIDC subject (`roles.toon`, R1) plus the read-only effective view — each granted capability
 * struck through when the role's own Access Profile denies it (roles.toon ∘ profile matrix, exactly
 * what `AccessGrants` enforces server-side at authentication). Role *assignment* stays in the IdP;
 * only the definitions are authored here. Every save is a full replace of the authored overlay:
 * editing a seed role moves it into the overlay, "Revert" drops it back to its seed defaults (or
 * removes a custom role entirely) — mirroring the settings-doc semantics of `PUT /access/roles`.
 */
@Component({
    selector: 'app-access-roles',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        ChipComponent,
        InspectoAlertComponent,
        InspectoSkeletonComponent,
    ],
    templateUrl: './access-roles.component.html',
})
export class AccessRolesComponent {
    private readonly api = inject(AccessService);
    private readonly lens = inject(LensService);
    private readonly dialog = inject(MatDialog);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly toastr = inject(ToastrService);

    /** Capability → the catalog action nodes bound to it (the profile-deny projection). */
    private readonly actionNodes: AccessNode[] = actionsOf(deriveDefaultAccessCatalog());
    private readonly idx: CatalogIndex = indexCatalog(deriveDefaultAccessCatalog());

    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly rows = signal<RoleDef[]>([]);
    /** Set ⇔ the authored roles.toon is unreadable (all grants suspended, fail-closed). */
    readonly docError = signal<string | null>(null);
    private readonly roleProfiles = signal<Record<string, AccessProfile>>({});

    readonly canEdit = computed(() => this.lens.canConfigureAccess());

    /** The full capability vocabulary: catalog-bound capabilities ∪ everything any role grants
     *  (the seed `super` role holds the complete route-gate set, so nothing gets lost). */
    readonly vocabulary = computed<string[]>(() => {
        const out: string[] = [];
        for (const n of this.actionNodes) {
            if (n.capability && !out.includes(n.capability)) out.push(n.capability);
        }
        for (const r of this.rows()) {
            for (const c of r.capabilities) if (!out.includes(c)) out.push(c);
        }
        return out;
    });

    /** Per role: its granted capabilities with the profile-deny overlay (the effective view). */
    readonly cards = computed(() =>
        this.rows().map((r) => ({
            role: r,
            capabilities: r.capabilities.map((c): CapabilityState => ({
                capability: c,
                deniedByProfile: this.deniedCaps(r.name).has(c),
            })),
        })));

    constructor() {
        this.load();
    }

    newRole(): void {
        this.openForm({});
    }

    edit(role: RoleDef): void {
        this.openForm({ role });
    }

    /** Drop a role's authored override: a seed role falls back to its defaults, a custom role is removed. */
    async revert(role: RoleDef): Promise<void> {
        if (!this.canEdit() || this.saving()) return;
        const ok = await this.confirm.confirmDestructive(
            `Remove the authored definition of '${role.name}'? A seed role falls back to its shipped defaults; a custom role is removed. Grants change on the next request.`,
            { title: 'Revert role?', confirmText: 'Revert' });
        if (!ok) return;
        this.persist(this.authoredOverlay().filter((r) => r.name !== role.name));
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private openForm(data: Pick<RoleFormData, 'role'>): void {
        if (!this.canEdit() || this.saving()) return;
        this.dialog
            .open<RoleFormDialog, RoleFormData, RoleDef | undefined>(RoleFormDialog, {
                width: '480px',
                data: {
                    ...data,
                    vocabulary: this.vocabulary(),
                    existingNames: this.rows().map((r) => r.name),
                },
            })
            .afterClosed()
            .subscribe((edited) => {
                if (!edited) return;
                this.persist([...this.authoredOverlay().filter((r) => r.name !== edited.name), edited]);
            });
    }

    /** The current authored overlay — exactly the rows `PUT /access/roles` replaces. */
    private authoredOverlay(): RoleDef[] {
        return this.rows().filter((r) => r.source === 'authored');
    }

    private persist(authored: RoleDef[]): void {
        this.saving.set(true);
        this.api.saveRoles(authored).subscribe({
            next: (doc) => {
                this.rows.set(doc.roles ?? []);
                this.docError.set(doc.error ?? null);
                this.saving.set(false);
                this.toastr.success('Role definitions saved — grants apply on the next request');
            },
            error: (err) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(err, 'Could not save the role definitions'));
            },
        });
    }

    private load(): void {
        forkJoin({
            roles: this.api.roles(),
            // The profile projection is an enrichment — a failing call must not blank the table.
            profiles: this.api.profiles().pipe(catchError(() => of([] as AccessProfile[]))),
        }).subscribe({
            next: ({ roles, profiles }) => {
                this.rows.set(roles.roles ?? []);
                this.docError.set(roles.error ?? null);
                const byRole: Record<string, AccessProfile> = {};
                for (const p of profiles ?? []) {
                    if (p.subjectType === 'role') byRole[p.subjectId] = p;
                }
                this.roleProfiles.set(byRole);
                this.loading.set(false);
            },
            error: () => this.loading.set(false),   // connectivity banner owns the unreachable case
        });
    }

    /** Capabilities this role's Access Profile denies via the catalog's action nodes (R2 semantics). */
    private deniedCaps(roleName: string): Set<string> {
        const profile = this.roleProfiles()[roleName];
        const denied = new Set<string>();
        if (!profile) return denied;
        for (const n of this.actionNodes) {
            if (n.capability && resolveGrant(n.id, profile.grants ?? {}, this.idx).effective === 'deny') {
                denied.add(n.capability);
            }
        }
        return denied;
    }
}

/** Flatten a catalog to its action nodes. */
function actionsOf(nodes: AccessNode[]): AccessNode[] {
    const out: AccessNode[] = [];
    const walk = (ns: AccessNode[]): void => {
        for (const n of ns) {
            if (n.kind === 'action') out.push(n);
            if (n.children?.length) walk(n.children);
        }
    };
    walk(nodes);
    return out;
}
