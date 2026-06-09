import { Injectable, inject } from '@angular/core';
import { CanActivate, Router, ActivatedRouteSnapshot } from '@angular/router';
import { TokenStore } from '../api/token-store.service';

export interface IUser {
  email: string;
  avatarUrl?: string;
}

const defaultPath = '/';
const authPath = 'connect';

/**
 * Token-based "auth" for Inspector. The backend (ControlApi) has no login endpoint — it uses scoped
 * bearer tokens supplied on the server (-Dcontrol.token / -Dassist.read.token). The operator pastes
 * those token(s) on the Connect screen; we keep them in sessionStorage (via {@link TokenStore}) and
 * an interceptor attaches them to API calls. "Logged in" simply means at least one token is held.
 */
@Injectable()
export class AuthService {
  private tokens = inject(TokenStore);

  get loggedIn(): boolean {
    return this.tokens.hasAny;
  }

  hasControl(): boolean { return !!this.tokens.control; }
  hasAssist(): boolean { return !!this.tokens.assist || !!this.tokens.control; }

  private _lastAuthenticatedPath: string = defaultPath;
  set lastAuthenticatedPath(value: string) {
    this._lastAuthenticatedPath = value;
  }

  constructor(private router: Router) { }

  /** Save the operator's token(s) and return to the last authenticated route. */
  async connect(controlToken: string | null, assistToken: string | null) {
    if (!controlToken?.trim() && !assistToken?.trim()) {
      return { isOk: false, message: 'Enter a control token and/or an assist token.' };
    }
    this.tokens.set(controlToken, assistToken);
    this.router.navigate([this._lastAuthenticatedPath]);
    return { isOk: true, data: await this.user() };
  }

  /** A synthetic user for the header/footer — describes the scopes currently held. */
  private async user(): Promise<IUser> {
    const scope = this.hasControl() ? 'control' : this.hasAssist() ? 'assist' : 'none';
    return { email: `operator (${scope})` };
  }

  async getUser() {
    try {
      return { isOk: true, data: this.loggedIn ? await this.user() : null };
    } catch {
      return { isOk: false, data: null };
    }
  }

  async logOut() {
    this.tokens.clear();
    this.router.navigate([`/${authPath}`]);
  }
}

@Injectable()
export class AuthGuardService implements CanActivate {
  constructor(private router: Router, private authService: AuthService) { }

  canActivate(route: ActivatedRouteSnapshot): boolean {
    const isLoggedIn = this.authService.loggedIn;
    const isAuthForm = (route.routeConfig?.path || defaultPath) === authPath;

    if (isLoggedIn && isAuthForm) {
      this.authService.lastAuthenticatedPath = defaultPath;
      this.router.navigate([defaultPath]);
      return false;
    }

    if (!isLoggedIn && !isAuthForm) {
      this.router.navigate([`/${authPath}`]);
    }

    if (isLoggedIn) {
      this.authService.lastAuthenticatedPath = route.routeConfig?.path || defaultPath;
    }

    return isLoggedIn || isAuthForm;
  }
}
