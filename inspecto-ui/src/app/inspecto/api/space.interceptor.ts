import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { environment } from '../../../environments/environment';
import { SpacesService } from './spaces.service';

/**
 * Server-global API paths that address the container/runtime, never a single space, so they must
 * NOT be space-prefixed. The `/spaces` group also covers `/spaces/_meta` and every per-space
 * `/spaces/{id}/…` call (export/import/datasources) — those already carry their space id explicitly.
 */
const SERVER_GLOBAL = ['/health', '/ready', '/metrics', '/spaces', '/bootstrap', '/auth'];

/**
 * Scopes every feature API call to the active space by rewriting `/api/<path>` →
 * `/api/spaces/<id>/<path>`. The backend's request seam strips the `/spaces/{id}` prefix, binds the
 * request to that space, and matches the unchanged route remainder — so every existing feature
 * service stays oblivious to multi-space.
 *
 * No-ops (so single-tenant behaviour is byte-identical) when there is no active space, for non-API
 * URLs (assets, i18n), and for the server-global paths above.
 */
export const spaceInterceptor: HttpInterceptorFn = (req, next) => {
    const id = inject(SpacesService).currentSpaceId();
    if (!id) return next(req);

    const base = environment.apiBaseUrl; // '/api'
    if (!req.url.startsWith(base + '/')) return next(req); // not a ControlApi call

    const rest = req.url.substring(base.length); // e.g. '/pipelines'
    if (SERVER_GLOBAL.some((p) => rest === p || rest.startsWith(p + '/'))) return next(req);

    return next(req.clone({ url: `${base}/spaces/${encodeURIComponent(id)}${rest}` }));
};
