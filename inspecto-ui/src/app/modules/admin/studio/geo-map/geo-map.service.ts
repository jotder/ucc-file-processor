import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { GeoCamera, GeoDisplayMode, GeoNote, GeoQuery, GeoSourceId } from 'app/inspecto/geo';
import { SavedViewStore } from 'app/inspecto/investigation';

/**
 * A saved geo investigation — a **Geo View** (GLOSSARY §11-Geo), persisted as the `geo-map-view`
 * component kind (mock-served until the backend `ComponentStore` enum widens — same constraint as
 * `link-analysis-view`).
 */
export interface GeoMapView {
    id: string;
    name: string;
    description?: string;
    sourceId: GeoSourceId;
    query: GeoQuery;
    /** Markers vs heatmap, captured with the view; absent = markers. */
    display?: GeoDisplayMode;
    /** Camera position captured with the view; absent = fit to data. */
    camera?: GeoCamera;
    /** Investigator annotations pinned to coordinates; absent = none. */
    notes?: GeoNote[];
}

/** View store — a thin kind/codec binding over the shared {@link SavedViewStore}. */
@Injectable({ providedIn: 'root' })
export class GeoMapService {
    private store = new SavedViewStore<GeoMapView>(inject(ComponentsService), 'geo-map-view', {
        toContent: (v) => ({
            name: v.name, description: v.description, sourceId: v.sourceId, query: v.query,
            display: v.display, camera: v.camera, notes: v.notes,
        }),
        fromContent: (id, content) => {
            const c = content as {
                name?: string; description?: string; sourceId?: GeoSourceId; query?: GeoQuery;
                display?: GeoDisplayMode; camera?: GeoCamera; notes?: GeoNote[];
            };
            return {
                id, name: c.name ?? id, description: c.description, sourceId: c.sourceId ?? 'dataset',
                query: c.query ?? {}, display: c.display, camera: c.camera, notes: c.notes,
            };
        },
    });

    list(): Observable<GeoMapView[]> {
        return this.store.list();
    }

    save(view: GeoMapView): Observable<GeoMapView> {
        return this.store.save(view);
    }

    remove(id: string): Observable<unknown> {
        return this.store.remove(id);
    }
}
