import { inject, Injectable } from '@angular/core';
import {
    HttpErrorResponse,
    HttpEvent,
    HttpHandlerFn,
    HttpInterceptorFn,
    HttpRequest,
} from '@angular/common/http';
import { BehaviorSubject, filter, Observable, take, throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { Router } from '@angular/router';
import { AuthService } from './auth-service';
import { environment } from 'environments/environment';

/**
 * Interceptor state — kept in a singleton service to preserve
 * isRefreshing / refreshTokenSubject across calls, since functional
 * interceptors are stateless by themselves.
 */
@Injectable({ providedIn: 'root' })
class AuthInterceptorState {
    isRefreshing = false;
    readonly refreshTokenSubject = new BehaviorSubject<string | null>(null);
}

/**
 * Functional HTTP interceptor that attaches the Bearer token and
 * handles 401 responses by transparently refreshing the access token.
 */
export const authInterceptor: HttpInterceptorFn = (
    request: HttpRequest<unknown>,
    next: HttpHandlerFn
): Observable<HttpEvent<unknown>> => {
    const authService = inject(AuthService);
    const router = inject(Router);
    const state = inject(AuthInterceptorState);
    

    const isTokenEndpoint = request.url.includes(`${environment.authVersion}/token`);
    const accessToken = authService.getAccessToken();

    const authReq =
        accessToken && !isTokenEndpoint
            ? addToken(request, accessToken)
            : request;

    return next(authReq).pipe(
        catchError((error: HttpErrorResponse) => {
            if (error.status === 401 && !isTokenEndpoint) {
                return handle401Error(authReq, next, authService, router, state);
            }
            return throwError(() => error);
        })
    );
};

/**
 * Clones the request and injects Authorization + timezone headers.
 */
function addToken(
    request: HttpRequest<unknown>,
    token: string | null
): HttpRequest<unknown> {
    if (!token) return request;

    const timezoneOffset = (new Date().getTimezoneOffset() * -1).toString();

    return request.clone({
        setHeaders: {
            Accept: 'application/json',
            Authorization: `Bearer ${token}`,
            TIMEZONE_COOKIE: timezoneOffset,
        },
    });
}

/**
 * Handles 401 by refreshing the token once and queuing concurrent
 * requests until the new token is available.
 */
function handle401Error(
    request: HttpRequest<unknown>,
    next: HttpHandlerFn,
    authService: AuthService,
    router: Router,
    state: AuthInterceptorState
): Observable<HttpEvent<unknown>> {
    if (!state.isRefreshing) {
        state.isRefreshing = true;
        state.refreshTokenSubject.next(null);

        return authService.renewAccessTokenUsingRefreshToken().pipe(
            switchMap((tokenPayload: any) => {
                state.isRefreshing = false;
                authService.saveTokens(tokenPayload);
                const newToken = authService.getAccessToken();
                state.refreshTokenSubject.next(newToken);
                return next(addToken(request, newToken));
            }),
            catchError((error: unknown) => {
                state.isRefreshing = false;
                state.refreshTokenSubject.next(null);
                authService.logout();
                return throwError(() => error);
            })
        );
    }

    // Refresh already in progress — queue this request until new token arrives
    return state.refreshTokenSubject.pipe(
        filter((token): token is string => token !== null),
        take(1),
        switchMap((token) => next(addToken(request, token)))
    );
}