import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { ConditionGroup, emptyGroup } from 'app/inspecto/query';
import { Dashboard, DashboardTile } from './dashboard-types';

/**
 * Dashboard store — persists {@link Dashboard}s as the `dashboard` component type (mock-served by `studio-mock`).
 * Mirrors `charts.service` / `datasets.service`; a dashboard is "just a composite component" on the model.
 */
@Injectable({ providedIn: 'root' })
export class DashboardsService {
    private components = inject(ComponentsService);

    list(): Observable<Dashboard[]> {
        return this.components.list('dashboard').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Dashboard> {
        return this.components.get('dashboard', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    save(dashboard: Dashboard): Observable<Dashboard> {
        return this.components.create('dashboard', { id: dashboard.id, ...toContent(dashboard) }).pipe(map(() => dashboard));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('dashboard', id);
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
