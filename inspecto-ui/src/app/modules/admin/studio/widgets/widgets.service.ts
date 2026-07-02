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

    save(widget: Widget): Observable<Widget> {
        return this.components.create('widget', { id: widget.id, ...toContent(widget) }).pipe(map(() => widget));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('widget', id);
    }
}

function toContent(w: Widget): Record<string, unknown> {
    return {
        name: w.name,
        datasetId: w.datasetId,
        vizType: w.vizType,
        controls: w.controls,
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
        vizType: (content['vizType'] as string) ?? 'bar',
        controls: (content['controls'] as ControlValues) ?? {},
        tags: (content['tags'] as string[]) ?? undefined,
        description: (content['description'] as string) ?? undefined,
        options: (content['options'] as WidgetOptions) ?? undefined,
    };
}
