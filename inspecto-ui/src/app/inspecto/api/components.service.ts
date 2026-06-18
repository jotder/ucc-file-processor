import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** The reusable component-registry kinds (mirrors backend `ComponentStore.WRITABLE_TYPES`). */
export type ComponentType = 'grammar' | 'schema' | 'transform' | 'sink';

/** The component kinds, in palette order, for the list/editor. */
export const COMPONENT_TYPES: ComponentType[] = ['grammar', 'schema', 'transform', 'sink'];

/**
 * One registry component (GET /components/{type}[/{id}]) — its kind, in-file identity, `<type>/<id>` ref,
 * and the parsed `.toon` content map. The content shape varies by kind (a grammar's CSV dialect, a schema's
 * typed fields, a transform's operator config, a sink's store/format/partitions).
 */
export interface ComponentDef {
    type: string;
    name: string;
    ref: string;
    content: Record<string, unknown>;
}

/** One produced relation in a preview (transform / schema): the rel key, its row count, and a bounded sample. */
export interface RelationPreview {
    rel: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

/** Transform/schema dry-run result (POST /components/{transform,schema}/{id}/test). */
export interface RelationsPreview {
    inputColumns: string[];
    relations: RelationPreview[];
}

/** Grammar parse result (POST /components/grammar/{id}/test). */
export interface GrammarPreview {
    columns: string[];
    rowCount: number;
    rows: Record<string, unknown>[];
    rejectedRows: number;
}

/** Sink scratch-validate result (POST /components/sink/{id}/test). */
export interface SinkPreview {
    store: string | null;
    rowCount: number;
    rows: Record<string, unknown>[];
    warnings: string[];
}

/**
 * Component registry CRUD + per-component dry-run/test (T18/T19, §7.1–7.2). Generalises the connection
 * write pattern to the non-secret kinds; writes are write-root gated (503 when disabled). The `/test`
 * endpoints run the component over a sample through the production logic on a throwaway DuckDB (no write).
 */
@Injectable({ providedIn: 'root' })
export class ComponentsService {
    private http = inject(HttpClient);

    /** Components of one kind (empty when no write root is configured). */
    list(type: ComponentType): Observable<ComponentDef[]> {
        return this.http.get<ComponentDef[]>(apiUrl(`/components/${type}`));
    }

    /** One component by kind/id. */
    get(type: ComponentType, id: string): Observable<ComponentDef> {
        return this.http.get<ComponentDef>(apiUrl(`/components/${type}/${encodeURIComponent(id)}`));
    }

    /** Create a component (write-root gated). `content` must include the `id`. 503/409/422 on failure. */
    create(type: ComponentType, content: Record<string, unknown>): Observable<ComponentDef> {
        return this.http.post<ComponentDef>(apiUrl(`/components/${type}`), content);
    }

    /** Replace a component's content (write-root gated). 503/404/422 on failure. */
    update(type: ComponentType, id: string, content: Record<string, unknown>): Observable<ComponentDef> {
        return this.http.put<ComponentDef>(apiUrl(`/components/${type}/${encodeURIComponent(id)}`), content);
    }

    /** Delete a component (write-root gated). 503/404/409 (in use) on failure. */
    remove(type: ComponentType, id: string): Observable<unknown> {
        return this.http.delete(apiUrl(`/components/${type}/${encodeURIComponent(id)}`));
    }

    /** Parse raw `sampleText` with a grammar's dialect (scratch-only). */
    testGrammar(id: string, sampleText: string): Observable<GrammarPreview> {
        return this.http.post<GrammarPreview>(apiUrl(`/components/grammar/${encodeURIComponent(id)}/test`), { sampleText });
    }

    /** TRY_CAST sample rows against a schema's typed fields → data/rejected split (scratch-only). */
    testSchema(id: string, sampleRows: Record<string, unknown>[]): Observable<RelationsPreview> {
        return this.http.post<RelationsPreview>(apiUrl(`/components/schema/${encodeURIComponent(id)}/test`), { sampleRows });
    }

    /** Run a transform over sample rows through the production RowShaper (scratch-only). */
    testTransform(id: string, sampleRows: Record<string, unknown>[]): Observable<RelationsPreview> {
        return this.http.post<RelationsPreview>(apiUrl(`/components/transform/${encodeURIComponent(id)}/test`), { sampleRows });
    }

    /** Scratch-validate a sink against sample rows (store/format/partition checks; no write). */
    testSink(id: string, sampleRows: Record<string, unknown>[]): Observable<SinkPreview> {
        return this.http.post<SinkPreview>(apiUrl(`/components/sink/${encodeURIComponent(id)}/test`), { sampleRows });
    }
}
