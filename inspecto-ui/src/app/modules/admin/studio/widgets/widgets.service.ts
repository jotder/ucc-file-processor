import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { ControlValues } from 'app/inspecto/viz';
import { Widget, WidgetOptions } from './widget-types';

/**
 * Widget store — persists {@link Widget}s as the `widget` component type (mock-served by the unified mock store). Mirrors
 * `datasets.service` / `rules.service`; the component model means a widget is "just a component" with a
 * mapping config.
 */
@Injectable({ providedIn: 'root' })
export class WidgetsService {
    private components = inject(ComponentsService);

    list(): Observable<Widget[]> {
        return this.components.list('widget').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Widget> {
        return this.components.get('widget', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    /** Create by default; pass `{update: true}` when editing an existing widget — the backend 409s a
     *  create on an existing id (id is immutable in the editors, so update never renames). */
    save(widget: Widget, opts?: { update?: boolean }): Observable<Widget> {
        const req$ = opts?.update
            ? this.components.update('widget', widget.id, toContent(widget))
            : this.components.create('widget', { id: widget.id, ...toContent(widget) });
        return req$.pipe(map(() => widget));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('widget', id);
    }
}

function toContent(w: Widget): Record<string, unknown> {
    return {
        name: w.name,
        datasetId: w.datasetId,
        queryId: w.queryId,
        vizType: w.vizType,
        controls: w.controls,
        viewId: w.viewId,
        tags: w.tags,
        description: w.description,
        options: w.options,
    };
}

function fromContent(name: string, content: Record<string, unknown>): Widget {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        datasetId: (content['datasetId'] as string) ?? '',
        queryId: (content['queryId'] as string) ?? undefined,
        vizType: (content['vizType'] as string) ?? 'bar',
        controls: (content['controls'] as ControlValues) ?? {},
        viewId: (content['viewId'] as string) ?? undefined,
        tags: (content['tags'] as string[]) ?? undefined,
        description: (content['description'] as string) ?? undefined,
        options: (content['options'] as WidgetOptions) ?? undefined,
    };
}
