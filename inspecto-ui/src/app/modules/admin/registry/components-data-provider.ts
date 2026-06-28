import { Injectable, inject } from '@angular/core';
import { firstValueFrom, map } from 'rxjs';
import type { ColumnMeta } from 'app/inspecto/query';
import { Component, ComponentPreview, DataProvider } from 'app/inspecto/component-model';
import { ComponentDef, ComponentType, ComponentsService } from 'app/inspecto/api';

/**
 * The first {@link DataProvider} implementation: resolves the platform's existing registry kinds
 * (grammar / schema / transform / sink / rule + Studio's dataset / chart / dashboard) through
 * {@link ComponentsService}, mapping the stored {@link ComponentDef} onto the model's {@link Component}.
 * Backend-agnostic — the components mock serves these today; the identical calls hit DuckDB-backed storage
 * once the backend `ComponentStore` enum is widened.
 *
 * P2 implements `list` (the reuse-graph's data source). `columns` / `preview` resolve a component's *output*
 * and have no consumer yet (P3 is the graph + reference table, not a preview pane) — they reject rather than
 * fabricate an empty result, and land when a consumer needs them.
 */
@Injectable({ providedIn: 'root' })
export class ComponentsDataProvider implements DataProvider {
    private components = inject(ComponentsService);

    list(kind: string): Promise<Component[]> {
        return firstValueFrom(this.components.list(kind as ComponentType).pipe(map((defs) => defs.map(toComponent))));
    }

    columns(_ref: { kind: string; id: string }): Promise<ColumnMeta[]> {
        return Promise.reject(new Error('ComponentsDataProvider.columns: not implemented until a consumer needs it'));
    }

    preview(_ref: { kind: string; id: string }, _limit?: number): Promise<ComponentPreview> {
        return Promise.reject(new Error('ComponentsDataProvider.preview: not implemented until a consumer needs it'));
    }
}

/** Map a stored {@link ComponentDef} (`{type,name,content}`) onto a model {@link Component}. */
function toComponent(def: ComponentDef): Component {
    const c: Component = {
        kind: def.type,
        id: def.name,
        name: (def.content['name'] as string) ?? def.name,
        config: def.content,
    };
    if (typeof def.content['space'] === 'string') c.space = def.content['space'] as string;
    return c;
}
