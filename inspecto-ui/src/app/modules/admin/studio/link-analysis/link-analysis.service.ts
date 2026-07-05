import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { GraphSourceId, GraphSourceQuery } from 'app/inspecto/graph';
import { SavedViewStore } from 'app/inspecto/investigation';
import { GraphDisplayOptions, GraphLayoutId } from 'app/modules/admin/catalog/graph-view.component';

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
    /** The chosen graph layout; absent = the default layered layout. */
    layout?: GraphLayoutId;
}

/** View store — a thin kind/codec binding over the shared {@link SavedViewStore}. */
@Injectable({ providedIn: 'root' })
export class LinkAnalysisService {
    private store = new SavedViewStore<LinkAnalysisView>(inject(ComponentsService), 'link-analysis-view', {
        toContent,
        fromContent,
    });

    list(): Observable<LinkAnalysisView[]> {
        return this.store.list();
    }

    save(view: LinkAnalysisView): Observable<LinkAnalysisView> {
        return this.store.save(view);
    }

    remove(id: string): Observable<unknown> {
        return this.store.remove(id);
    }
}

function toContent(v: LinkAnalysisView): Record<string, unknown> {
    return { name: v.name, description: v.description, sourceId: v.sourceId, query: v.query, display: v.display, layout: v.layout };
}

function fromContent(id: string, content: Record<string, unknown>): LinkAnalysisView {
    const c = content as {
        name?: string; description?: string; sourceId?: GraphSourceId; query?: GraphSourceQuery;
        display?: GraphDisplayOptions; layout?: GraphLayoutId;
    };
    return {
        id,
        name: c.name ?? id,
        description: c.description,
        sourceId: c.sourceId ?? 'lineage',
        query: c.query ?? {},
        display: c.display,
        layout: c.layout,
    };
}
