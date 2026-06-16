import { HttpErrorResponse, HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';

/** Prefix an API route path with the configured base ('' in prod, '/api' behind the dev proxy). */
export function apiUrl(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/**
 * Extract a human-readable message from a failed API call.
 *
 * ControlApi reports errors as JSON `{ "error": "…" }`, so the happy path is `err.error.error`.
 * But when the response isn't JSON at all — e.g. the static SPA fallback returns `index.html`
 * (misrouted path, dev proxy off, or an auth redirect) — Angular's HttpClient fails to parse it
 * and sets `err.error` to `{ error: <SyntaxError>, text: '<!doctype …' }`. Surfacing that raw
 * SyntaxError ("Unexpected token '<'…") to the user is meaningless, so we detect the non-JSON
 * case and return the caller's fallback instead.
 */
export function apiErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    // A status-0 / parse failure means we never got a real JSON body — use the fallback.
    if (err.status === 0) return fallback;
    const body = err.error;
    const msg = body?.error;
    if (typeof msg === 'string' && msg.trim()) return msg;
    return fallback;
  }
  return fallback;
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
