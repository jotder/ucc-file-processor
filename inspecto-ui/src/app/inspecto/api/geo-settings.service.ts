import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** Map preferences (Settings → Map): the Phase 4 tile-server config seam. `null` = the bundled offline basemap. */
export interface GeoSettings {
    tileServerUrl: string | null;
}

/** GET/PUT `/settings/geo` — mock-served until the backend grows a settings store. */
@Injectable({ providedIn: 'root' })
export class GeoSettingsService {
    private http = inject(HttpClient);

    get(): Observable<GeoSettings> {
        return this.http.get<GeoSettings>(apiUrl('/settings/geo'));
    }

    save(settings: GeoSettings): Observable<GeoSettings> {
        return this.http.put<GeoSettings>(apiUrl('/settings/geo'), settings);
    }
}
