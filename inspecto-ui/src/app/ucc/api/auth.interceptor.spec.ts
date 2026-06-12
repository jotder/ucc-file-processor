import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { authInterceptor } from './auth.interceptor';
import { TokenStore } from './token-store.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        TokenStore,
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('attaches the bearer token when one is held', () => {
    TestBed.inject(TokenStore).set('ctrl', null);
    http.get('/pipelines').subscribe();
    const req = httpMock.expectOne('/pipelines');
    expect(req.request.headers.get('Authorization')).toBe('Bearer ctrl');
    req.flush([]);
  });

  it('sends no Authorization header when no token is held', () => {
    http.get('/health').subscribe();
    const req = httpMock.expectOne('/health');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ status: 'UP' });
  });
});
