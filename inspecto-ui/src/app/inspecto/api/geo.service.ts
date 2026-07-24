import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** The wire shape of a `POST /geo/projection` request (mirrors the studio's `GeoProjection` mapping). */
export interface GeoProjectionRequest {
  dataset: string;
  latCol: string;
  lonCol: string;
  entityCol?: string;
  kindCol?: string;
  timeCol?: string;
  attrCols?: string[];
  limit?: number;
}

export interface GeoPointRow {
  id: string;
  lat: number;
  lon: number;
  kind: string;
  label?: string;
  time?: number;
  attrs?: Record<string, unknown>;
}

export interface GeoRouteRow {
  id: string;
  from: string;
  to: string;
  kind: string;
  weight: number;
}

export interface GeoProjectionResult {
  points: GeoPointRow[];
  routes: GeoRouteRow[];
  truncated: boolean;
  /** Rows dropped for a missing/invalid coordinate. */
  skipped: number;
}

/** The wire shape of a `POST /geo/routes` request (mirrors the studio's `RouteProjection` mapping). */
export interface GeoRoutesRequest {
  dataset: string;
  fromLatCol: string;
  fromLonCol: string;
  toLatCol: string;
  toLonCol: string;
  fromCol?: string;
  toCol?: string;
  kindCol?: string;
  limit?: number;
}

/**
 * Geo Map backend (Phase 4): the real DuckDB-side projection/aggregation over a Dataset — the
 * server half of the Geo Map studio's `dataset`/`od-routes` GeoSources. Offline/mock mode answers
 * 501 (see `mock/handlers/geo.handler.ts`) and the studio falls back to its client-side row fold,
 * mirroring the Link Analysis studio's `InvService`/`entity-projection` backend-first pattern.
 */
@Injectable({ providedIn: 'root' })
export class GeoService {
  private http = inject(HttpClient);

  project(req: GeoProjectionRequest): Observable<GeoProjectionResult> {
    return this.http.post<GeoProjectionResult>(apiUrl('/geo/projection'), req);
  }

  routes(req: GeoRoutesRequest): Observable<GeoProjectionResult> {
    return this.http.post<GeoProjectionResult>(apiUrl('/geo/routes'), req);
  }
}
