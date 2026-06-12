import { inject, Injectable } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TokenStore } from './api/token-store.service';

/**
 * Token-based "auth" for the inspector (ported from inspector-ui). ControlApi has no login
 * endpoint — the operator pastes the scoped bearer token(s) configured on the server
 * (-Dcontrol.token / -Dassist.read.token) on the Connect screen; TokenStore keeps them in
 * sessionStorage and the auth interceptor attaches them. "Logged in" = at least one token held.
 */
@Injectable({ providedIn: 'root' })
export class UccAuthService {
    private tokens = inject(TokenStore);
    private router = inject(Router);

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

    /** Save the operator's token(s) and return to the last authenticated route. */
    connect(controlToken: string | null, assistToken: string | null): { isOk: boolean; message?: string } {
        if (!controlToken?.trim() && !assistToken?.trim()) {
            return { isOk: false, message: 'Enter a control token and/or an assist token.' };
        }
        this.tokens.set(controlToken, assistToken);
        this.router.navigate([this.lastAuthenticatedPath]);
        return { isOk: true };
    }

    logout(): void {
        this.tokens.clear();
        this.router.navigate(['/connect']);
    }
}

/** Route guard: UCC screens require a token; otherwise bounce to the Connect screen. */
export const uccAuthGuard: CanActivateFn = (route, state) => {
    const auth = inject(UccAuthService);
    const router = inject(Router);
    if (!auth.loggedIn) {
        return router.parseUrl('/connect');
    }
    auth.lastAuthenticatedPath = state.url || '/';
    return true;
};
