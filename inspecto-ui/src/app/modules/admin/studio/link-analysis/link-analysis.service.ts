import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { GraphSourceId, GraphSourceQuery } from 'app/inspecto/graph';
import { GraphDisplayOptions } from 'app/modules/admin/catalog/graph-view.component';

/**
 * A saved investigation — a **Link-Analysis View** (GLOSSARY §11), persisted as the
 * `link-analysis-view` component kind (mock-served by the unified store; the real backend
 * `ComponentStore` enum is still closed — same constraint as every new kind).
 */
export interface LinkAnalysisView {
    id: string;
    name: string;
    description?: string;
    sourceId: GraphSourceId;
    query: GraphSourceQuery;
    /** Presentation (labels + per-kind colours) captured with the view; absent = defaults. */
    display?: GraphDisplayOptions;
}

/** View store — mirrors `widgets.service` / `datasets.service` over the components seam. */
@Injectable({ providedIn: 'root' })
export class LinkAnalysisService {
    private components = inject(ComponentsService);

    list(): Observable<LinkAnalysisView[]> {
        return this.components.list('link-analysis-view').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    save(view: LinkAnalysisView): Observable<LinkAnalysisView> {
        return this.components
            .create('link-analysis-view', { id: view.id, ...toContent(view) })
            .pipe(map(() => view));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('link-analysis-view', id);
    }
}

function toContent(v: LinkAnalysisView): Record<string, unknown> {
    return { name: v.name, description: v.description, sourceId: v.sourceId, query: v.query, display: v.display };
}

function fromContent(id: string, content: Record<string, unknown>): LinkAnalysisView {
    const c = content as {
        name?: string; description?: string; sourceId?: GraphSourceId; query?: GraphSourceQuery;
        display?: GraphDisplayOptions;
    };
    return {
        id,
        name: c.name ?? id,
        description: c.description,
        sourceId: c.sourceId ?? 'lineage',
        query: c.query ?? {},
        display: c.display,
    };
}
