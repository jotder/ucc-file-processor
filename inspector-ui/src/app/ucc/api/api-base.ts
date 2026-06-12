import { HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';

/** Prefix an API route path with the configured base ('' in prod, '/api' behind the dev proxy). */
export function apiUrl(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/** Build HttpParams from a plain object, skipping null/undefined/'' values. */
export function toParams(obj: Record<string, unknown>): HttpParams {
  let p = new HttpParams();
  for (const [k, v] of Object.entries(obj)) {
    if (v !== undefined && v !== null && v !== '') {
      p = p.set(k, Array.isArray(v) ? v.join(',') : String(v));
    }
  }
  return p;
}
