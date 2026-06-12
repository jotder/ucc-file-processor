// auth-service.ts
import { inject, Injectable, OnDestroy } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { BehaviorSubject, catchError, map, Observable, throwError } from 'rxjs';
import { Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { AppProperties } from '../commons/app.properties';
import { SecurityPrincipal } from '../commons/security-principal';
import { AppHttpService } from '../commons/app.http.service';
import { AUTH_HTTP_CLIENT } from './auth-http-client.token';
import { environment } from 'environments/environment';

enum AuthBroadcastMessage {
    TOKENS_UPDATED = 'TOKENS_UPDATED',
    LOGGED_OUT     = 'LOGGED_OUT',
}

@Injectable({
    providedIn: 'root',
})
export class AuthService extends AppHttpService implements OnDestroy {

    private readonly REFRESH_BEFORE_EXPIRATION_MS = 30 * 1000;

    // -------------------------------------------------------------------------
    // Inject AUTH_HTTP_CLIENT instead of HttpClient — bypasses authInterceptor,
    // breaking the NG0200 circular dependency.
    // -------------------------------------------------------------------------
    private readonly authHttp = inject(AUTH_HTTP_CLIENT);

    private readonly props             = inject(AppProperties);
    private readonly securityPrincipal = inject(SecurityPrincipal);
    private readonly router            = inject(Router);
    // private readonly toastr            = inject(ToastrService);

    private readonly authChannel: BroadcastChannel | null =
        typeof BroadcastChannel !== 'undefined'
            ? new BroadcastChannel('auth_channel')
            : null;

    private refreshTimer: ReturnType<typeof setTimeout> | null = null;
    private refreshingInProgress = false;

    private readonly isAuthenticatedSubject = new BehaviorSubject<boolean>(
        this.hasValidAccessToken()
    );
    readonly isAuthenticated$ = this.isAuthenticatedSubject.asObservable();

    constructor(httpClient: HttpClient) {
        // Pass the standard HttpClient to AppHttpService as before.
        // AppHttpService uses it for non-auth calls — those DO go through interceptors.
        super(httpClient);

        this.authChannel?.addEventListener('message', (event: MessageEvent) => {
            switch (event.data.type) {
                case AuthBroadcastMessage.TOKENS_UPDATED:
                    this.isAuthenticatedSubject.next(this.hasValidAccessToken());
                    break;
                case AuthBroadcastMessage.LOGGED_OUT:
                    this.clearTokens(false);
                    break;
            }
        });

        this.isAuthenticatedSubject.next(this.hasValidAccessToken());
        this.startTokenRefreshTimer();
    }

    ngOnDestroy(): void {
        this.clearRefreshTimer();
        this.authChannel?.close();
    }

    // -------------------------------------------------------------------------
    // Token operations — use authHttp (no interceptor) to avoid circular calls
    // -------------------------------------------------------------------------

    /**
     * Authorization code → access token (first login).
     * Uses authHttp to skip the Bearer interceptor on the token endpoint.
     */
    retrieveToken(code: string): Observable<any> {
        const body = new FormData();
        body.append('grant_type', 'authorization_code');
        body.append('client_id', this.props.appClientId);
        body.append('client_secret', this.props.appClientSecret);
        body.append('redirect_uri', this.props.appRedirectUri);
        body.append('code', code);

        const apiUrl = `${environment.authServerUrl}${environment.authVersion}/token`;

        return this.authHttp.post(apiUrl, body, { headers: this.getBasicAuthHeader() }).pipe(
            catchError((err) => {
                console.error('Token retrieval failed:', err);
                return throwError(() => err);
            })
        );
    }

    /**
     * Refresh token → new access token.
     * Uses authHttp to skip the Bearer interceptor (token has already expired).
     */
    renewAccessTokenUsingRefreshToken(): Observable<any> {
        const body = new HttpParams()
            .set('grant_type', 'refresh_token')
            .set('refresh_token', this.getRefreshToken() ?? '');

        const apiUrl = `${environment.authServerUrl}${environment.authVersion}/token`;

        return this.authHttp.post(apiUrl, body, { headers: this.getBasicAuthHeader() }).pipe(
            map((response: any) => {
                this.saveTokens(response);
                return response;
            }),
            catchError((err) => {
                console.error('Token refresh failed:', err);
                return throwError(() => err);
            })
        );
    }

    // -------------------------------------------------------------------------
    // Unchanged methods
    // -------------------------------------------------------------------------

    saveTokens(data: any): void {
        this.securityPrincipal.setAccessToken(data['access_token']);
        this.securityPrincipal.setRefreshToken(data['refresh_token']);
        this.securityPrincipal.decodeJwtToken(data['access_token']);
        this.isAuthenticatedSubject.next(true);
        this.startTokenRefreshTimer();
        this.authChannel?.postMessage({ type: AuthBroadcastMessage.TOKENS_UPDATED });
    }

    logout(): void {
        this.clearTokens(true);
        this.router.navigate(['/login']);
    }

    hasValidAccessToken(): boolean {
        const token      = this.getAccessToken();
        const expiration = this.getAccessTokenExpiration();
        return !!token && expiration !== null && Date.now() < expiration;
    }

    getBasicAuthHeader(): HttpHeaders {
        const credentials = window.btoa(
            `${this.props.appClientId}:${this.props.appClientSecret}`
        );
        return new HttpHeaders({ Authorization: `Basic ${credentials}` });
    }

    getAccessToken(): string {
        return this.securityPrincipal.getAccessToken();
    }

    getRefreshToken(): string | null {
        return this.securityPrincipal.getRefreshToken();
    }

    getAccessTokenExpiration(): number | null {
        const expiresAt = this.securityPrincipal.getExpirationTime();
        return expiresAt ? parseInt(expiresAt, 10) * 1000 : null;
    }

    private clearTokens(broadcast = true): void {
        this.securityPrincipal.clear();
        this.isAuthenticatedSubject.next(false);
        if (broadcast) {
            this.authChannel?.postMessage({ type: AuthBroadcastMessage.LOGGED_OUT });
        }
    }

    private startTokenRefreshTimer(): void {
        this.clearRefreshTimer();
        const expiration = this.getAccessTokenExpiration();
        if (!this.hasValidAccessToken() || !expiration) return;

        const timeUntilRefresh = expiration - Date.now() - this.REFRESH_BEFORE_EXPIRATION_MS;

        if (timeUntilRefresh > 0) {
            this.refreshTimer = setTimeout(() => this.handleProactiveRefresh(), timeUntilRefresh);
        } else {
            this.handleProactiveRefresh();
        }
    }

    private clearRefreshTimer(): void {
        if (this.refreshTimer !== null) {
            clearTimeout(this.refreshTimer);
            this.refreshTimer = null;
        }
    }

    private handleProactiveRefresh(): void {
        if (this.refreshingInProgress) return;
        this.refreshingInProgress = true;
        this.renewAccessTokenUsingRefreshToken().subscribe({
            error:    () => (this.refreshingInProgress = false),
            complete: () => (this.refreshingInProgress = false),
        });
    }
}