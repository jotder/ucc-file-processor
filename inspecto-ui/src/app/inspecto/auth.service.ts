import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, Observable, of } from 'rxjs';
import { AUTH_HTTP_CLIENT } from 'app/modules/auth/auth-http-client.token';
import { apiUrl } from './api/api-base';
import { TokenStore } from './api/token-store.service';

/**
 * Token-based "auth" for the inspector (ported from inspector-ui). ControlApi has no login
 * endpoint — the operator pastes the scoped bearer token(s) configured on the server
 * (-Dcontrol.token / -Dassist.read.token) on the Connect screen; TokenStore keeps them in
 * sessionStorage and the auth interceptor attaches them. "Logged in" = at least one token held.
 */
@Injectable({ providedIn: 'root' })
export class InspectoAuthService {
    private tokens = inject(TokenStore);
    private router = inject(Router);
    /** Interceptor-free client: the connect-time probe must not trip the global 401 redirect/toast. */
    private probe = inject(AUTH_HTTP_CLIENT);

    lastAuthenticatedPath = '/';

    get loggedIn(): boolean {
        return this.tokens.hasAny;
    }

    hasControl(): boolean {
        return !!this.tokens.control;
    }

    hasAssist(): boolean {
        return !!this.tokens.assist || !!this.tokens.control;
    }

    /** Currently-held scope, for display. */
    get scope(): 'control' | 'assist' | 'none' {
        return this.hasControl() ? 'control' : this.hasAssist() ? 'assist' : 'none';
    }

    /**
     * Validate the operator's token(s) against the backend, then — only on success — persist them
     * and return to the last authenticated route. Probing `/catalog` (ASSIST_READ) accepts either a
     * control or an assist token (control satisfies the assist scopes by hierarchy), so a single
     * probe covers both. We send the candidate token by hand on the interceptor-free client so the
     * token isn't stored until it's known good and a probe 401 doesn't fire the global redirect.
     */
    connect(controlToken: string | null, assistToken: string | null): Observable<{ isOk: boolean; message?: string }> {
        const control = controlToken?.trim() || null;
        const assist = assistToken?.trim() || null;
        if (!control && !assist) {
            return of({ isOk: false, message: 'Enter a control token and/or an assist token.' });
        }
        const candidate = control ?? assist!; // control is the superuser; else the assist token
        return this.probe
            .get(apiUrl('/catalog'), { headers: { Authorization: `Bearer ${candidate}` } })
            .pipe(
                map(() => {
                    this.tokens.set(control, assist);
                    this.router.navigate([this.lastAuthenticatedPath]);
                    return { isOk: true };
                }),
                catchError((err: HttpErrorResponse) => of({ isOk: false, message: this.connectError(err) })),
            );
    }

    /** Map a failed connect probe to an operator-facing reason. */
    private connectError(err: HttpErrorResponse): string {
        if (err.status === 0) return 'Backend not reachable — check that the server is running.';
        if (err.status === 401) {
            return 'Token rejected. Check it matches the server and that the server was started '
                + 'with a token (-Dcontrol.token / CONTROL_TOKEN); otherwise its routes stay locked.';
        }
        return `Could not validate token (HTTP ${err.status}).`;
    }

    logout(): void {
        this.tokens.clear();
        this.router.navigate(['/connect']);
    }
}

/** Route guard: Inspecto screens require a token; otherwise bounce to the Connect screen. */
export const inspectoAuthGuard: CanActivateFn = (route, state) => {
    const auth = inject(InspectoAuthService);
    const router = inject(Router);
    if (!auth.loggedIn) {
        return router.parseUrl('/connect');
    }
    auth.lastAuthenticatedPath = state.url || '/';
    return true;
};
