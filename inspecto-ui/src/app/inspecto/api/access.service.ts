import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

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
}
