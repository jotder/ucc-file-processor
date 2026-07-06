import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SessionService } from './session.service';

/** The BFF session routes manage their own credential (a one-time code, or the httpOnly cookie), so
 *  they must never carry the bearer nor trigger the 401→refresh retry (that would recurse). */
const SESSION_PATHS = ['/auth/exchange', '/auth/refresh', '/auth/logout'];

/**
 * Standard-edition bearer attachment + silent refresh (W6d). Attaches `Authorization: Bearer <token>`
 * to control-plane calls when a session is live, and on a 401 tries one silent refresh (from the
 * httpOnly cookie) then retries the original request; a failed refresh drops the session and routes to
 * sign-in.
 *
 * <p><b>No-op on Personal / offline.</b> When {@link SessionService.authMode} isn't `'oidc'` this
 * passes every request straight through — the auth-free core stays byte-for-byte unchanged. In offline
 * mock mode the {@link mockApiInterceptor} short-circuits before this runs, so it never engages there
 * either. Only attaches to same-origin ControlApi calls (`/api…`), never to assets/i18n.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
    const session = inject(SessionService);
    if (session.authMode() !== 'oidc') return next(req);

    const base = environment.apiBaseUrl;
    const isApi = req.url.startsWith(base + '/') || req.url.startsWith(base + '?') || req.url === base;
    const isSessionRoute = SESSION_PATHS.some((p) => req.url.includes(p));
    if (!isApi || isSessionRoute) return next(req);

    const token = session.token();
    const authed = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

    return next(authed).pipe(
        catchError((err: HttpErrorResponse) => {
            if (err.status !== 401) return throwError(() => err);
            // One silent refresh from the cookie, then retry the original request with the new token.
            return session.refresh().pipe(
                switchMap((fresh) => next(req.clone({ setHeaders: { Authorization: `Bearer ${fresh}` } }))),
                catchError((refreshErr) => {
                    session.onAuthLost();
                    return throwError(() => refreshErr);
                }),
            );
        }),
    );
};
