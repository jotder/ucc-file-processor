import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AssistService, AssistSettings } from './assist.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

describe('AssistService (model settings, v4.1)', () => {
  let svc: AssistService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AssistService, provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(AssistService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GET /assist/settings for settings()', () => {
    let received: AssistSettings | undefined;
    svc.settings().subscribe((s) => (received = s));
    const body: AssistSettings = { supported: true, provider: 'anthropic', apiKeySet: true };
    httpMock.expectOne((r) => r.method === 'GET' && r.url === `${base}/assist/settings`).flush(body);
    expect(received?.provider).toBe('anthropic');
    expect(received?.apiKeySet).toBe(true);
  });

  it('POST /assist/settings for saveSettings(), key rides the request body only', () => {
    svc.saveSettings({ provider: 'anthropic', apiKey: 'sk-test' }).subscribe();
    const req = httpMock.expectOne(`${base}/assist/settings`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.apiKey).toBe('sk-test');
    req.flush({ supported: true, provider: 'anthropic', apiKeySet: true });
  });

  it('POST /assist/settings/test for testSettings()', () => {
    svc.testSettings().subscribe();
    const req = httpMock.expectOne(`${base}/assist/settings/test`);
    expect(req.request.method).toBe('POST');
    req.flush({ supported: true, medium: { provider: 'anthropic:claude-sonnet-4-6 (MEDIUM)', ok: true, latencyMs: 412 } });
  });

  it('settings() does not get confused with the intent catch-all', () => {
    svc.run('explain-entity', {}).subscribe();
    httpMock.expectOne(`${base}/assist/explain-entity`).flush({});
  });
});
