import { beforeEach, describe, expect, it } from 'vitest';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { spaceInterceptor } from './space.interceptor';
import { SpacesService } from './spaces.service';

const base = environment.apiBaseUrl; // '/api'

let http: HttpClient;
let mock: HttpTestingController;

/** Wire an HttpClient with the interceptor over a stub service whose active space is `id`. */
function setup(id: string | null): void {
    TestBed.configureTestingModule({
        providers: [
            provideHttpClient(withInterceptors([spaceInterceptor])),
            provideHttpClientTesting(),
            { provide: SpacesService, useValue: { currentSpaceId: signal(id) } },
        ],
    });
    http = TestBed.inject(HttpClient);
    mock = TestBed.inject(HttpTestingController);
}

/** Assert that a request to `requested` is actually issued to `finalUrl`. */
function expectUrl(requested: string, finalUrl: string): void {
    http.get(requested).subscribe();
    mock.expectOne(finalUrl).flush({});
    mock.verify();
}

describe('spaceInterceptor', () => {
    describe('with an active space', () => {
        beforeEach(() => setup('acme'));

        it('prefixes a feature API call with /spaces/<id>', () =>
            expectUrl(`${base}/pipelines`, `${base}/spaces/acme/pipelines`));

        it('leaves /health server-global', () => expectUrl(`${base}/health`, `${base}/health`));

        it('leaves /metrics server-global', () => expectUrl(`${base}/metrics`, `${base}/metrics`));

        it('does not prefix the /spaces list', () => expectUrl(`${base}/spaces`, `${base}/spaces`));

        it('does not double-prefix a per-space endpoint', () =>
            expectUrl(`${base}/spaces/acme/export`, `${base}/spaces/acme/export`));

        it('does not prefix the /spaces/_meta probe', () =>
            expectUrl(`${base}/spaces/_meta`, `${base}/spaces/_meta`));

        it('ignores non-API URLs (assets / i18n)', () =>
            expectUrl('./i18n/en.json', './i18n/en.json'));
    });

    describe('with no active space (single-tenant)', () => {
        beforeEach(() => setup(null));

        it('passes feature calls through unprefixed (byte-identical)', () =>
            expectUrl(`${base}/pipelines`, `${base}/pipelines`));
    });
});
