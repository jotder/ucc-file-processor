import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { apiUrl } from 'app/inspecto/api/api-base';
import { ConditionGroup, emptyGroup } from 'app/inspecto/query';
import { Dashboard, DashboardTile } from './dashboard-types';

/** `POST /dashboards/{id}/share` — the minted public link (BI-6). `url` is the API resolve path;
 *  the shareable link the user copies is the app viewer route `/share/{token}`. */
export interface DashboardShareLink {
    token: string;
    url: string;
    dashboard: string;
    expiresAt: string;
}

/**
 * Dashboard store — persists {@link Dashboard}s as the `dashboard` component type (mock-served by the unified mock store).
 * Mirrors `widgets.service` / `datasets.service`; a dashboard is "just a composite component" on the model.
 */
@Injectable({ providedIn: 'root' })
export class DashboardsService {
    private components = inject(ComponentsService);
    private http = inject(HttpClient);

    list(): Observable<Dashboard[]> {
        return this.components.list('dashboard').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Dashboard> {
        return this.components.get('dashboard', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    /** Create by default; pass `{update: true}` when modifying an existing dashboard (editor save,
     *  add-tile) — the backend 409s a create on an existing id (id is immutable on edit). */
    save(dashboard: Dashboard, opts?: { update?: boolean }): Observable<Dashboard> {
        const req$ = opts?.update
            ? this.components.update('dashboard', dashboard.id, toContent(dashboard))
            : this.components.create('dashboard', { id: dashboard.id, ...toContent(dashboard) });
        return req$.pipe(map(() => dashboard));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('dashboard', id);
    }

    /** Mint a public, expiring share link for a saved dashboard (BI-6). 503 when sharing is disabled server-side. */
    share(id: string, ttlHours?: number): Observable<DashboardShareLink> {
        return this.http.post<DashboardShareLink>(
            apiUrl('/dashboards/' + encodeURIComponent(id) + '/share'),
            ttlHours ? { ttl_hours: ttlHours } : {},
        );
    }
}

function toContent(d: Dashboard): Record<string, unknown> {
    return { name: d.name, tiles: d.tiles, filter: d.filter ?? null };
}

function fromContent(name: string, content: Record<string, unknown>): Dashboard {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        tiles: (content['tiles'] as DashboardTile[]) ?? [],
        filter: (content['filter'] as ConditionGroup) ?? emptyGroup('AND'),
    };
}
