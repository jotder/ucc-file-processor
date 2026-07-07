import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BrandingService } from './branding.service';
import { environment } from '../../../environments/environment';

const url = environment.apiBaseUrl + '/v1/settings/branding';

describe('BrandingService', () => {
  let svc: BrandingService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [BrandingService, provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(BrandingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('falls back to the shipped defaults when the fetched doc is all null', () => {
    TestBed.tick(); // flush the constructor effect → GET /settings/branding
    httpMock.expectOne(url).flush({ id: 'branding', logoDataUrl: null, caption: null, footerText: null });

    expect(svc.logoUrl()).toBe(environment.appLogo);
    expect(svc.caption()).toBe('Unveil stories from your data');
    expect(svc.footerText()).toBe(environment.footerText);
  });

  it('save() PUTs and updates the live accessors', () => {
    TestBed.tick();
    httpMock.expectOne(url).flush({ id: 'branding', logoDataUrl: null, caption: null, footerText: null });

    const doc = { logoDataUrl: 'data:image/png;base64,AAAA', caption: 'My brand', footerText: 'ACME Corp' };
    svc.save(doc).subscribe();
    const req = httpMock.expectOne(url);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(doc);
    req.flush({ id: 'branding', ...doc });

    expect(svc.logoUrl()).toBe(doc.logoDataUrl);
    expect(svc.caption()).toBe('My brand');
    expect(svc.footerText()).toBe('ACME Corp');
  });

  it('getFor()/saveFor() address a specific space explicitly', () => {
    TestBed.tick();
    httpMock.expectOne(url).flush({ id: 'branding', logoDataUrl: null, caption: null, footerText: null });

    const spaceUrl = environment.apiBaseUrl + '/v1/spaces/beta/settings/branding';
    svc.getFor('beta').subscribe();
    expect(httpMock.expectOne(spaceUrl).request.method).toBe('GET');

    svc.saveFor('beta', { logoDataUrl: null, caption: 'Beta', footerText: null }).subscribe();
    const put = httpMock.expectOne(spaceUrl);
    expect(put.request.method).toBe('PUT');
    put.flush({ id: 'branding', logoDataUrl: null, caption: 'Beta', footerText: null });
    // 'beta' is not the active space (null → 'default'), so the header signal stays on defaults.
    expect(svc.caption()).toBe('Unveil stories from your data');
  });
});
