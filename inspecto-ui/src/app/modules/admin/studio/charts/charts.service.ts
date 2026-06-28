import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { ControlValues } from 'app/inspecto/viz';
import { Chart } from './chart-types';

/**
 * Chart store — persists {@link Chart}s as the `chart` component type (mock-served by `studio-mock`). Mirrors
 * `datasets.service` / `rules.service`; the component model means a chart is "just a component" with a
 * mapping config.
 */
@Injectable({ providedIn: 'root' })
export class ChartsService {
    private components = inject(ComponentsService);

    list(): Observable<Chart[]> {
        return this.components.list('chart').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Chart> {
        return this.components.get('chart', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    save(chart: Chart): Observable<Chart> {
        return this.components.create('chart', { id: chart.id, ...toContent(chart) }).pipe(map(() => chart));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('chart', id);
    }
}

function toContent(c: Chart): Record<string, unknown> {
    return { name: c.name, datasetId: c.datasetId, vizType: c.vizType, controls: c.controls };
}

function fromContent(name: string, content: Record<string, unknown>): Chart {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        datasetId: (content['datasetId'] as string) ?? '',
        vizType: (content['vizType'] as string) ?? 'bar',
        controls: (content['controls'] as ControlValues) ?? {},
    };
}
