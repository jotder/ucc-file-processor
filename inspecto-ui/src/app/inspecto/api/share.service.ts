import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One shared component as the public endpoint returns it: id + raw content (typed by the feature side). */
export interface SharedComponent {
  id: string;
  content: Record<string, unknown>;
}

/** `GET /public/dashboards/{token}` — the shared dashboard, its widgets, and the link's expiry. */
export interface SharedDashboard {
  dashboard: SharedComponent;
  widgets: SharedComponent[];
  expiresAt: string;
}

/** One measure of a public BI query (the backend's validated agg/field pair — never SQL text). */
export interface PublicMeasure {
  agg: string;
  field?: string;
}

/** The `POST /public/dashboards/{token}/query` body — the /bi/query spec, fenced to the share's datasets. */
export interface PublicQueryBody {
  dataset: string;
  measures?: PublicMeasure[];
  groupBy?: string[];
  orderBy?: { field: string; dir: 'asc' | 'desc' }[];
  limit?: number;
}

export interface PublicQueryResult {
  rows: Record<string, unknown>[];
  rowCount: number;
  truncated: boolean;
}

/**
 * Public dashboard sharing (BI-6): the anonymous embed surface. The token IS the credential — both
 * calls ride `/public/dashboards/{token}` (auth-exempt, space-global) and every invalid/expired/tampered
 * token is an indistinguishable 404. Offline/mock mode answers 501 (tokens are HMAC-verified server-side;
 * see `mock/handlers/public-dashboards.handler.ts`), so the embed viewer shows its clean error state.
 */
@Injectable({ providedIn: 'root' })
export class ShareService {
  private http = inject(HttpClient);

  /** Resolve a share token to its dashboard + widget definitions (read-only). */
  resolve(token: string): Observable<SharedDashboard> {
    return this.http.get<SharedDashboard>(apiUrl('/public/dashboards/' + encodeURIComponent(token)));
  }

  /** Run one widget's aggregation, fenced server-side to the datasets the shared dashboard references. */
  query(token: string, body: PublicQueryBody): Observable<PublicQueryResult> {
    return this.http.post<PublicQueryResult>(
      apiUrl('/public/dashboards/' + encodeURIComponent(token) + '/query'), body);
  }
}
