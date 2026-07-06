import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/**
 * Standard-edition route guard (W6d): redirect to the sign-in screen when OIDC is on and there is no
 * live session yet. A pure pass-through on Personal / offline (`authMode !== 'oidc'`) — the auth-free
 * core keeps booting straight to the app with no guard effect. Session state is already resolved by
 * {@link SessionService.init} (an app initializer that runs before routing), so this reads signals only.
 */
export const authGuard: CanActivateFn = () => {
    const session = inject(SessionService);
    if (!session.loginRequired()) return true;
    return inject(Router).createUrlTree(['/sign-in']);
};
