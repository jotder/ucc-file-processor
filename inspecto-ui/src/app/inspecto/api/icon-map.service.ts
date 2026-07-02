import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One configurable icon rule: a glyph name (from the curated GLYPH_LIBRARY) + a colour swatch. */
export interface IconRule {
    glyph: string;
    color: string;
}

/**
 * Configurable processor-icon map. Keyed by a processor **type** string (`parser.dsv`) for sub-type
 * specificity, or a **category** (`PARSE`) for the family default — the type entry wins over the category
 * entry, and anything unmapped falls back to the built-in per-kind glyph.
 */
export type IconMap = Record<string, IconRule>;

/** CRUD for the configurable icon map (mock-backed via the unified mock store; a per-space backend config later). */
@Injectable({ providedIn: 'root' })
export class IconMapService {
    private http = inject(HttpClient);

    get(): Observable<IconMap> {
        return this.http.get<IconMap>(apiUrl('/config/icon-map'));
    }

    save(map: IconMap): Observable<IconMap> {
        return this.http.put<IconMap>(apiUrl('/config/icon-map'), map);
    }
}
