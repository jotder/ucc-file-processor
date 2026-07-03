import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, forkJoin, map, Observable, of, tap } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/**
 * A hosted space (project) manifest — GET /spaces returns these (camelCase, server-side).
 * One Inspecto server hosts many isolated spaces; the active space scopes every other API call
 * (see {@link spaceInterceptor}).
 */
export interface Space {
    id: string;
    displayName: string;
    description: string;
    createdAt: string;
}

/** POST /spaces request body — note the snake_case keys the ControlApi expects. */
export interface CreateSpaceRequest {
    id: string;
    display_name?: string;
    description?: string;
    /** A Space-Template id ({@link SpaceTemplateInfo}) — the new space is seeded from its blueprint. */
    template?: string;
}

/**
 * A **Space Template** manifest — GET /spaces/templates. A reusable blueprint bundle of Components
 * (Connections → Sources → Pipelines → Datasets → Widgets → Dashboards → Rules + sample data) that
 * instantiates a new Space (Template is the Type, the Space the Instance — docs/GLOSSARY.md).
 */
export interface SpaceTemplateInfo {
    id: string;
    name: string;
    /** One-line vertical pitch, shown on the gallery card (also the created space's default description). */
    tagline: string;
    description: string;
    /** Heroicons name for the gallery card. */
    icon: string;
    /** Human summary of the blueprint's contents, rendered as card chips. */
    contents: string[];
}

/** DELETE /spaces/{id} response. */
export interface DeleteSpaceResult {
    id: string;
    deleted: boolean;
    purged: boolean;
}

/** POST /spaces/{id}/import success body — what was unpacked + made live. */
export interface BundleImportResult {
    kind: string;
    imported: string[];
    pipelines: string[];
    overwritten: boolean;
}

/** One structural-validation finding for a bundle file (ERROR fails the import preview). */
export interface PreviewFinding {
    severity: string; // 'ERROR' | 'WARNING'
    fieldPath: string;
    message: string;
}

/**
 * POST /spaces/{id}/import/preview — a dry-run that parses a bundle and reports what *would* happen
 * without writing: the data sources + files it carries, id conflicts with the target space, and
 * per-file structural findings. `valid` is false iff any finding is an ERROR.
 */
export interface ImportPreview {
    kind: string;
    sourceSpace: string | null;
    dataSources: string[];
    files: string[];
    hasSpaceToon: boolean;
    conflicts: string[];
    findings: Record<string, PreviewFinding[]>;
    valid: boolean;
}

const STORAGE_KEY = 'inspecto.currentSpace';

/**
 * Holds the global multi-space state (active space + the hosted list) as signals, and wraps the
 * server-global `/spaces` CRUD + per-space bundle export/import endpoints.
 *
 * The active space scopes every other API call: {@link spaceInterceptor} reads {@link currentSpaceId}
 * and rewrites `/api/<path>` → `/api/spaces/<id>/<path>`. A `null` active space means "no prefix"
 * (single-tenant / default namespace) so single-space behaviour is byte-identical. The id is restored
 * from `localStorage` in the constructor — synchronously, before the first HTTP call — so the very
 * first request already carries the right space.
 *
 * The `/spaces`, `/spaces/_meta` and per-space `/spaces/{id}/…` endpoints here are all addressed
 * literally (they start with `/spaces`), so the interceptor leaves them untouched — no double-prefix.
 */
@Injectable({ providedIn: 'root' })
export class SpacesService {
    private http = inject(HttpClient);

    /** Every hosted space's manifest (sorted by id, server-side). */
    readonly availableSpaces = signal<Space[]>([]);
    /** Whether this server is the CRUD-capable discover runtime (GET /spaces/_meta). */
    readonly multiSpace = signal(false);
    /** The active space id, or null for the un-prefixed default namespace. */
    readonly currentSpaceId = signal<string | null>(this.restore());

    /** The active space's manifest, when it is in the loaded list. */
    readonly currentSpace = computed<Space | null>(
        () => this.availableSpaces().find((s) => s.id === this.currentSpaceId()) ?? null,
    );
    /** Show the switcher only on a multi-space server that has spaces to switch between. */
    readonly showSwitcher = computed(() => this.multiSpace() && this.availableSpaces().length > 0);

    /**
     * Load the capability flag + space list and reconcile the active selection: keep a still-valid
     * saved id, otherwise fall back to `default` (if present) or the first space; clear it entirely on a
     * single-tenant server. Degrades to single-tenant on any error so the rest of the app keeps working.
     */
    refresh(): Observable<Space[]> {
        return forkJoin({
            meta: this.http
                .get<{ multiSpace: boolean }>(apiUrl('/spaces/_meta'))
                .pipe(catchError(() => of({ multiSpace: false }))),
            spaces: this.http.get<Space[]>(apiUrl('/spaces')).pipe(catchError(() => of([] as Space[]))),
        }).pipe(
            tap(({ meta, spaces }) => {
                this.multiSpace.set(meta.multiSpace);
                this.availableSpaces.set(spaces);
                this.reconcile(meta.multiSpace, spaces);
            }),
            map(({ spaces }) => spaces),
        );
    }

    /** Set the active space (or null for the default namespace) and persist it across reloads. */
    selectSpace(id: string | null): void {
        this.currentSpaceId.set(id);
        if (typeof localStorage === 'undefined') return;
        if (id) localStorage.setItem(STORAGE_KEY, id);
        else localStorage.removeItem(STORAGE_KEY);
    }

    // ── server-global CRUD (/spaces) ─────────────────────────────────────────────

    /** Create + boot a space (multi-space servers only). 400 bad id, 409 dup / single-tenant. */
    create(req: CreateSpaceRequest): Observable<Space> {
        return this.http.post<Space>(apiUrl('/spaces'), req);
    }

    /** The Space-Template catalog (blueprint bundles "New space from template" instantiates). */
    templates(): Observable<SpaceTemplateInfo[]> {
        return this.http.get<SpaceTemplateInfo[]>(apiUrl('/spaces/templates'));
    }

    /** Deregister + drain a space; `purge` also deletes its files. 404 unknown, 409 single-tenant. */
    remove(id: string, purge: boolean): Observable<DeleteSpaceResult> {
        return this.http.delete<DeleteSpaceResult>(apiUrl(`/spaces/${encodeURIComponent(id)}`), {
            params: toParams({ purge: purge ? 'true' : '' }),
        });
    }

    /** Create + boot a brand-new space seeded from a bundle zip (server-global). 400 bad id/bundle, 409 dup. */
    createFromBundle(id: string, zip: Blob): Observable<Space> {
        return this.http.post<Space>(apiUrl('/spaces/import'), zip, {
            params: toParams({ id }),
            headers: { 'Content-Type': 'application/zip' },
        });
    }

    // ── per-space data sources + export/import (/spaces/{id}/…) ───────────────────

    /** The data-source (pipeline) ids hosted by a space. */
    dataSources(spaceId: string): Observable<string[]> {
        return this.http.get<string[]>(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/datasources`));
    }

    /** Download one data source's config bundle as a zip. */
    exportDataSource(spaceId: string, ds: string): Observable<Blob> {
        return this.http.get(
            apiUrl(`/spaces/${encodeURIComponent(spaceId)}/datasources/${encodeURIComponent(ds)}/export`),
            { responseType: 'blob' },
        );
    }

    /** Download a whole space's config as a zip. */
    exportSpace(spaceId: string): Observable<Blob> {
        return this.http.get(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/export`), {
            responseType: 'blob',
        });
    }

    /** Dry-run a bundle import into a space — what would be written, conflicts, findings. No writes. */
    importPreview(spaceId: string, zip: Blob): Observable<ImportPreview> {
        return this.http.post<ImportPreview>(
            apiUrl(`/spaces/${encodeURIComponent(spaceId)}/import/preview`),
            zip,
            { headers: { 'Content-Type': 'application/zip' } },
        );
    }

    /** Import a bundle into an existing space. 409 {error, conflicts} unless `overwrite`. */
    importBundle(spaceId: string, zip: Blob, overwrite: boolean): Observable<BundleImportResult> {
        return this.http.post<BundleImportResult>(
            apiUrl(`/spaces/${encodeURIComponent(spaceId)}/import`),
            zip,
            {
                params: toParams({ on_conflict: overwrite ? 'overwrite' : '' }),
                headers: { 'Content-Type': 'application/zip' },
            },
        );
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private restore(): string | null {
        if (typeof localStorage === 'undefined') return null;
        return localStorage.getItem(STORAGE_KEY);
    }

    private reconcile(multiSpace: boolean, spaces: Space[]): void {
        if (!multiSpace) {
            this.selectSpace(null); // single-tenant: no prefix, byte-identical default namespace
            return;
        }
        const saved = this.currentSpaceId();
        if (saved && spaces.some((s) => s.id === saved)) return; // still valid — keep it
        const fallback = spaces.find((s) => s.id === 'default')?.id ?? spaces[0]?.id ?? null;
        this.selectSpace(fallback);
    }
}
