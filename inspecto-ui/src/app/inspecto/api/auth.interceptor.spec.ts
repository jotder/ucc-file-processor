import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { environment } from '../../../environments/environment';
import { authInterceptor } from './auth.interceptor';
import { SessionService } from './session.service';

const base = environment.apiBaseUrl;

/** A minimal stand-in for SessionService exposing only what the interceptor touches. */
function stubSession(mode: 'none' | 'oidc', token: string | null) {
    return {
        authMode: signal(mode),
        token: () => token,
        refresh: vi.fn(() => of('at-refreshed')),
        onAuthLost: vi.fn(),
    };
}

describe('authInterceptor (W6d)', () => {
    let http: HttpClient;
    let httpMock: HttpTestingController;

    function setup(session: ReturnType<typeof stubSession>): void {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(withInterceptors([authInterceptor])),
                provideHttpClientTesting(),
                { provide: SessionService, useValue: session },
            ],
        });
        http = TestBed.inject(HttpClient);
        httpMock = TestBed.inject(HttpTestingController);
    }

    afterEach(() => httpMock.verify());

    it('is a no-op on Personal (authMode none) — no Authorization header', () => {
        setup(stubSession('none', 'ignored'));
        http.get(`${base}/pipelines`).subscribe();
        const req = httpMock.expectOne(`${base}/pipelines`);
        expect(req.request.headers.has('Authorization')).toBe(false);
        req.flush([]);
    });

    it('attaches the bearer on OIDC when a token is present', () => {
        setup(stubSession('oidc', 'at-1'));
        http.get(`${base}/pipelines`).subscribe();
        const req = httpMock.expectOne(`${base}/pipelines`);
        expect(req.request.headers.get('Authorization')).toBe('Bearer at-1');
        req.flush([]);
    });

    it('never attaches the bearer to the session routes themselves', () => {
        setup(stubSession('oidc', 'at-1'));
        http.post(`${base}/auth/refresh`, {}).subscribe();
        const req = httpMock.expectOne(`${base}/auth/refresh`);
        expect(req.request.headers.has('Authorization')).toBe(false);
        req.flush({ accessToken: 'x' });
    });

    it('on 401 it refreshes once and retries with the new token', () => {
        const session = stubSession('oidc', 'at-old');
        setup(session);
        let body: unknown;
        http.get(`${base}/pipelines`).subscribe((b) => (body = b));

        httpMock.expectOne((r) => r.headers.get('Authorization') === 'Bearer at-old')
            .flush({ error: 'nope' }, { status: 401, statusText: 'Unauthorized' });

        expect(session.refresh).toHaveBeenCalledOnce();
        httpMock.expectOne((r) => r.headers.get('Authorization') === 'Bearer at-refreshed').flush([{ ok: true }]);
        expect(body).toEqual([{ ok: true }]);
    });

    it('drops the session when the refresh also fails', () => {
        const session = stubSession('oidc', 'at-old');
        session.refresh = vi.fn(() => throwError(() => new Error('refresh failed')));
        setup(session);
        let errored = false;
        http.get(`${base}/pipelines`).subscribe({ error: () => (errored = true) });
        httpMock.expectOne(`${base}/pipelines`).flush({}, { status: 401, statusText: 'Unauthorized' });
        expect(session.onAuthLost).toHaveBeenCalledOnce();
        expect(errored).toBe(true);
    });
});
