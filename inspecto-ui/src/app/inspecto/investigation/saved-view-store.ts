import { Observable, map } from 'rxjs';
import { ComponentDef, ComponentType, ComponentsService } from 'app/inspecto/api';

/**
 * Shared investigation-shell machinery (Link Analysis + Geo Map Analysis studios).
 * Design: docs/superpower/geo-map-analysis-plan.md §"Shared WITH modification".
 */

/** How a studio's view maps to/from a Component's `content` payload. */
export interface SavedViewCodec<TView extends { id: string }> {
    toContent(view: TView): Record<string, unknown>;
    fromContent(id: string, content: Record<string, unknown>): TView;
}

/**
 * A saved-investigation store over the components seam — the generalization of the
 * `link-analysis-view` list/save/remove pattern. Each studio keeps a thin `@Injectable`
 * service that delegates to one of these with its kind + codec (`geo-map-view`,
 * `link-analysis-view`, …). Mock-served until the backend `ComponentStore` enum widens.
 */
export class SavedViewStore<TView extends { id: string }> {
    constructor(
        private components: ComponentsService,
        private kind: ComponentType,
        private codec: SavedViewCodec<TView>,
    ) {}

    list(): Observable<TView[]> {
        return this.components
            .list(this.kind)
            .pipe(map((defs: ComponentDef[]) => defs.map((d) => this.codec.fromContent(d.name, d.content))));
    }

    /** One view by id, or `null` when it doesn't exist (the mock GET returns a `null` body, not a 404). */
    get(id: string): Observable<TView | null> {
        return this.components
            .get(this.kind, id)
            .pipe(map((d: ComponentDef | null) => (d ? this.codec.fromContent(d.name, d.content) : null)));
    }

    /** Create by default; pass `{update: true}` when the id already exists (save-under-same-name
     *  overwrite) — the backend 409s a create on an existing id. */
    save(view: TView, opts?: { update?: boolean }): Observable<TView> {
        const req$ = opts?.update
            ? this.components.update(this.kind, view.id, this.codec.toContent(view))
            : this.components.create(this.kind, { id: view.id, ...this.codec.toContent(view) });
        return req$.pipe(map(() => view));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove(this.kind, id);
    }
}
