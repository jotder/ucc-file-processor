import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { ParameterDef, QueryModel } from 'app/inspecto/query';
import { Query, QueryConfig, QueryType } from './query-types';

/**
 * Query store — persists {@link Query}s through the component registry as the `query` component type
 * (mock-served by the unified mock store; real persistence once the backend storage enum is widened).
 * Mirrors `DatasetsService`: a query is "just a component" with a {@link QueryConfig} body.
 */
@Injectable({ providedIn: 'root' })
export class QueriesService {
    private components = inject(ComponentsService);

    list(): Observable<Query[]> {
        return this.components.list('query').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Query> {
        return this.components.get('query', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    /** Create by default; pass `{update: true}` when editing an existing query — the backend 409s a
     *  create on an existing id (id is immutable in the editor, so update never renames). */
    save(q: Query, opts?: { update?: boolean }): Observable<Query> {
        const req$ = opts?.update
            ? this.components.update('query', q.id, toContent(q))
            : this.components.create('query', { id: q.id, ...toContent(q) });
        return req$.pipe(map(() => q));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('query', id);
    }
}

function toContent(q: Query): Record<string, unknown> {
    const config: QueryConfig = {
        type: q.type,
        datasetId: q.datasetId ?? null,
        sourceName: q.sourceName,
        text: q.text ?? null,
        model: q.model ?? null,
        parameters: q.parameters,
    };
    return { name: q.name, description: q.description, ...config } as Record<string, unknown>;
}

function fromContent(name: string, content: Record<string, unknown>): Query {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        description: content['description'] as string | undefined,
        type: (content['type'] as QueryType) ?? 'sql',
        datasetId: (content['datasetId'] as string | null) ?? null,
        sourceName: content['sourceName'] as string | undefined,
        text: (content['text'] as string | null) ?? null,
        model: (content['model'] as QueryModel | null) ?? null,
        parameters: (content['parameters'] as ParameterDef[]) ?? [],
    };
}
