import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from 'environments/environment';
import { apiUrl } from './api-base';
import { SpacesService } from './spaces.service';

/**
 * Per-space product branding — the sidebar logo, the caption below it, and the footer text. Each field
 * is `null` when unset, meaning "use the shipped default". Persisted via `GET|PUT /settings/branding`
 * (mock-served, per space — see the settings mock handler).
 */
export interface Branding {
    logoDataUrl: string | null;
    caption: string | null;
    footerText: string | null;
}

/** The shipped defaults used whenever a branding field is unset. */
const DEFAULTS = {
    logoUrl: environment.appLogo,
    caption: 'Unveil stories from your data',
    footerText: environment.footerText,
};

/**
 * Holds the active space's branding as a signal and wraps the `/settings/branding` endpoint. The layout
 * header binds to the computed accessors ({@link logoUrl}/{@link caption}/{@link footerText}), which fall
 * back to the shipped defaults. Branding is per space, so an `effect` re-fetches whenever the active space
 * changes ({@link SpacesService.currentSpaceId}); `save()` updates the signal so the header reflects edits
 * live.
 */
@Injectable({ providedIn: 'root' })
export class BrandingService {
    private http = inject(HttpClient);
    private spaces = inject(SpacesService);

    private brand = signal<Branding>({ logoDataUrl: null, caption: null, footerText: null });

    readonly logoUrl = computed(() => this.brand().logoDataUrl || DEFAULTS.logoUrl);
    readonly caption = computed(() => this.brand().caption || DEFAULTS.caption);
    readonly footerText = computed(() => this.brand().footerText || DEFAULTS.footerText);

    constructor() {
        // Reload branding when the active space changes (per-space document).
        effect(() => {
            this.spaces.currentSpaceId(); // track
            this.get().subscribe({
                next: (b) => this.brand.set(b),
                error: () => this.brand.set({ logoDataUrl: null, caption: null, footerText: null }),
            });
        });
    }

    get(): Observable<Branding> {
        return this.http.get<Branding>(apiUrl('/settings/branding'));
    }

    save(branding: Branding): Observable<Branding> {
        return this.http
            .put<Branding>(apiUrl('/settings/branding'), branding)
            .pipe(tap((saved) => this.brand.set(saved)));
    }

    /** Read a specific space's branding (addressed explicitly, so it works for any space, not just the active one). */
    getFor(spaceId: string): Observable<Branding> {
        return this.http.get<Branding>(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/settings/branding`));
    }

    /** Save a specific space's branding. If it is the active space, the header reflects it live. */
    saveFor(spaceId: string, branding: Branding): Observable<Branding> {
        return this.http
            .put<Branding>(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/settings/branding`), branding)
            .pipe(tap((saved) => {
                if (spaceId === (this.spaces.currentSpaceId() ?? 'default')) this.brand.set(saved);
            }));
    }
}
