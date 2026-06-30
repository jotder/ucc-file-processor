import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { Dataset, DatasetColumn, DatasetConfig, DatasetKind, NamedMeasure } from './dataset-types';

/**
 * Dataset store — persists {@link Dataset}s through the component registry as the `dataset` component type
 * (mock-served by `studio-mock.interceptor` today; real persistence once the backend storage enum is widened).
 * Mirrors `inspecto/rule/rules.service.ts` — the component model means a dataset is "just a component" with a
 * {@link DatasetConfig} body.
 */
@Injectable({ providedIn: 'root' })
export class DatasetsService {
    private components = inject(ComponentsService);

    list(): Observable<Dataset[]> {
        return this.components
            .list('dataset')
            .pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Dataset> {
        return this.components.get('dataset', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    save(ds: Dataset): Observable<Dataset> {
        return this.components.create('dataset', { id: ds.id, ...toContent(ds) }).pipe(map(() => ds));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('dataset', id);
    }
}

function toContent(d: Dataset): Record<string, unknown> {
    const config: DatasetConfig = {
        kind: d.kind,
        sourceName: d.sourceName,
        query: d.query ?? null,
        physicalRef: d.physicalRef ?? null,
        columns: d.columns,
        measures: d.measures,
        viz: d.viz ?? null,
    };
    return { name: d.name, ...config } as Record<string, unknown>;
}

function fromContent(name: string, content: Record<string, unknown>): Dataset {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        kind: (content['kind'] as DatasetKind) ?? 'virtual',
        sourceName: (content['sourceName'] as string) ?? 'data',
        query: (content['query'] as Dataset['query']) ?? null,
        physicalRef: (content['physicalRef'] as string | null) ?? null,
        columns: (content['columns'] as DatasetColumn[]) ?? [],
        measures: (content['measures'] as NamedMeasure[]) ?? [],
        viz: (content['viz'] as Dataset['viz']) ?? null,
    };
}
