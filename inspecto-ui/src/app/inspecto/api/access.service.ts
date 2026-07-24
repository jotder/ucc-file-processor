import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/**
 * Lens access configuration (`/access/*` — design `docs/superpower/lens-access-config-design.md`,
 * vocabulary `docs/GLOSSARY.md` §1-A): the **Access Catalog** (the tree of menus → panes →
 * capability-bound action nodes, derived by the UI and snapshotted to the backend) and one
 * **Access Profile** per subject. Subjects are Lenses today (visibility shaping, honor system);
 * under RBAC the same documents carry `subjectType: 'role'` and are enforced server-side.
 */

export type AccessNodeKind = 'menu' | 'pane' | 'action';
export type AccessGrant = 'allow' | 'deny';
export type AccessSubjectType = 'lens' | 'role';

export interface AccessNode {
    id: string;
    label: string;
    kind: AccessNodeKind;
    icon?: string;
    link?: string;
    /** For `kind: 'action'` — the one LensService capability this functionality binds to. */
    capability?: string;
    children?: AccessNode[];
}

export interface AccessCatalog {
    version: number;
    nodes: AccessNode[];
}

/** Sparse grants: absent nodeId = inherit from the nearest explicit ancestor; root default = allow. */
export interface AccessProfile {
    subjectType: AccessSubjectType;
    subjectId: string;
    label: string;
    grants: Record<string, AccessGrant>;
}

/** The profile's document/URL id — `<subjectType>-<subjectId>` (e.g. `lens-business`). */
export function accessProfileId(p: Pick<AccessProfile, 'subjectType' | 'subjectId'>): string {
    return `${p.subjectType}-${p.subjectId}`;
}

/** One role's grants (RBAC R1 — the authorable `roles.toon` table behind every OIDC subject). */
export interface RoleDef {
    name: string;
    capabilities: string[];
    /** SEC-7d data scoping; absent = the role contributes no scoping (unscoped). */
    dataScopes?: string[];
    /** GET only: whether the row comes from the authored doc or the shipped seed defaults. */
    source?: 'authored' | 'seed';
}

/** `GET /access/roles` — the effective table; `error` set ⇔ the authored doc is unreadable
 *  (all role grants suspended, fail-closed) until fixed or re-saved. */
export interface RolesDoc {
    roles: RoleDef[];
    error?: string;
}

/** One Access Policy (ABAC A2/A3): an allow/deny over subject/resource/environment attributes. */
export interface PolicyDef {
    name: string;
    effect: AccessGrant;
    target?: { actions?: string[]; resourceKinds?: string[] };
    when?: string;
    /** GET only: `authored` = written to the doc; `seed` = an engine-resident built-in (A4 space
     *  isolation) surfaced read-only so operators see the denies they never wrote. */
    source?: 'authored' | 'seed';
}

/** `GET /access/policies` — authored policies plus the engine's seed policies (Enterprise only);
 *  `error` set ⇔ the authored doc is unreadable (the engine denies, fail-closed) until fixed. */
export interface PoliciesDoc {
    policies: PolicyDef[];
    error?: string;
}

/** One policy's contribution to an explain trace. A policy decides only when both are true. */
export interface PolicyEvaluation {
    name: string;
    effect: string;
    source: string;
    targeted: boolean;
    conditionHeld: boolean;
}

/** `GET /access/explain` — a "why denied?" dry-run for the caller's own session. `enabled:false`
 *  when there is no policy engine (Personal/Standard) or no authenticated subject. */
export interface ExplainResult {
    enabled: boolean;
    reason?: string;
    subject?: string;
    action?: string;
    route?: string;
    resourceKind?: string;
    decision?: 'ALLOW' | 'DENY' | 'ABSTAIN';
    matchedPolicy?: string | null;
    trace?: PolicyEvaluation[];
}

@Injectable({ providedIn: 'root' })
export class AccessService {
    private http = inject(HttpClient);

    catalog(): Observable<AccessCatalog> {
        return this.http.get<AccessCatalog>(apiUrl('/access/catalog'));
    }

    saveCatalog(catalog: AccessCatalog): Observable<AccessCatalog> {
        return this.http.put<AccessCatalog>(apiUrl('/access/catalog'), catalog);
    }

    profiles(): Observable<AccessProfile[]> {
        return this.http.get<AccessProfile[]>(apiUrl('/access/profiles'));
    }

    saveProfile(profile: AccessProfile): Observable<AccessProfile> {
        return this.http.put<AccessProfile>(
            apiUrl(`/access/profiles/${encodeURIComponent(accessProfileId(profile))}`), profile);
    }

    deleteProfile(id: string): Observable<{ deleted: string }> {
        return this.http.delete<{ deleted: string }>(apiUrl(`/access/profiles/${encodeURIComponent(id)}`));
    }

    /** The effective policies — authored rows tagged `source:authored`, plus the engine's seed
     *  policies tagged `source:seed` (Enterprise only). */
    policies(): Observable<PoliciesDoc> {
        return this.http.get<PoliciesDoc>(apiUrl('/access/policies'));
    }

    /** "Why denied?" dry-run for the current session against a hypothetical route/method/resource. */
    explain(p: { route: string; method?: string; resourceKind?: string }): Observable<ExplainResult> {
        return this.http.get<ExplainResult>(apiUrl('/access/explain'), {
            params: toParams({ route: p.route, method: p.method, resourceKind: p.resourceKind }),
        });
    }

    roles(): Observable<RolesDoc> {
        return this.http.get<RolesDoc>(apiUrl('/access/roles'));
    }

    /** Full replace of the AUTHORED overlay (settings-doc discipline): roles named here override
     *  their seed entry (an empty capability list revokes); seed roles not named keep their
     *  defaults. Returns the resulting effective table. */
    saveRoles(authored: RoleDef[]): Observable<RolesDoc> {
        return this.http.put<RolesDoc>(apiUrl('/access/roles'), {
            roles: authored.map((r) => ({
                name: r.name,
                capabilities: r.capabilities,
                ...(r.dataScopes?.length ? { dataScopes: r.dataScopes } : {}),
            })),
        });
    }
}
