import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { SessionService } from './session.service';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths
const tick = () => new Promise((r) => setTimeout(r, 0));

describe('SessionService (W6d edition switch)', () => {
    let svc: SessionService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        sessionStorage.clear();
        TestBed.configureTestingModule({
            providers: [SessionService, provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
        });
        svc = TestBed.inject(SessionService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('Personal/offline bootstrap ⇒ authMode none, no login, no /auth/refresh', async () => {
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'personal', features: { authMode: 'none' } });
        await done;
        expect(svc.authMode()).toBe('none');
        expect(svc.loginRequired()).toBe(false);
        httpMock.expectNone(`${base}/auth/refresh`); // never attempts a session under Personal
    });

    it('OIDC bootstrap + refresh 401 ⇒ authenticated false, loginRequired true', async () => {
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'standard', features: { authMode: 'oidc' } });
        await tick();
        httpMock.expectOne(`${base}/auth/refresh`).flush({ error: 'no session' }, { status: 401, statusText: 'Unauthorized' });
        await done;
        expect(svc.authMode()).toBe('oidc');
        expect(svc.authenticated()).toBe(false);
        expect(svc.loginRequired()).toBe(true);
    });

    it('OIDC bootstrap + refresh 200 ⇒ resumes the session (token + authenticated)', async () => {
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'standard', features: { authMode: 'oidc' } });
        await tick();
        httpMock.expectOne(`${base}/auth/refresh`).flush({ accessToken: 'at-resumed', expiresIn: 300 });
        await done;
        expect(svc.authenticated()).toBe(true);
        expect(svc.token()).toBe('at-resumed');
        expect(svc.loginRequired()).toBe(false);
    });

    it('completeLogin rejects a mismatched state without any HTTP call (CSRF guard)', async () => {
        sessionStorage.setItem('inspecto.pkce.state', 'expected');
        sessionStorage.setItem('inspecto.pkce.verifier', 'v');
        let ok = true;
        svc.completeLogin('code', 'DIFFERENT').subscribe((r) => (ok = r));
        await tick();
        expect(ok).toBe(false);
        httpMock.expectNone(`${base}/auth/exchange`);
    });

    it('completeLogin posts code+verifier to /auth/exchange and stores the access token', async () => {
        sessionStorage.setItem('inspecto.pkce.state', 's1');
        sessionStorage.setItem('inspecto.pkce.verifier', 'verifier-1');
        let ok = false;
        svc.completeLogin('the-code', 's1').subscribe((r) => (ok = r));
        const req = httpMock.expectOne(`${base}/auth/exchange`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toMatchObject({ code: 'the-code', codeVerifier: 'verifier-1' });
        req.flush({ accessToken: 'at-new', expiresIn: 300 });
        await tick();
        expect(ok).toBe(true);
        expect(svc.token()).toBe('at-new');
        // completeLogin re-reads bootstrap to populate capabilities from the new subject.
        httpMock.expectOne(`${base}/bootstrap`).flush({ session: { authenticated: true, capabilities: ['canOperateRuns'] } });
        await tick();
        expect(svc.capabilities()).toEqual(['canOperateRuns']);
    });

    it('logout clears local state and POSTs /auth/logout', async () => {
        // seed an authenticated state
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ features: { authMode: 'oidc' } });
        await tick();
        httpMock.expectOne(`${base}/auth/refresh`).flush({ accessToken: 'at', expiresIn: 300 });
        await done;
        expect(svc.authenticated()).toBe(true);

        svc.logout();
        httpMock.expectOne(`${base}/auth/logout`).flush({ loggedOut: true });
        await tick();
        expect(svc.authenticated()).toBe(false);
        expect(svc.token()).toBeNull();
    });
});
