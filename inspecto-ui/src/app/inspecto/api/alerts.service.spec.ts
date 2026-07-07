import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AlertsService, FiredAlert } from './alerts.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

describe('AlertsService (alert engine, v4.1 B5)', () => {
  let svc: AlertsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AlertsService, provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(AlertsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GET /alerts with limit for recent()', () => {
    let received: FiredAlert[] = [];
    svc.recent(10).subscribe((a) => (received = a));
    const req = httpMock.expectOne((r) => r.method === 'GET' && r.url === `${base}/alerts`);
    expect(req.request.params.get('limit')).toBe('10');
    req.flush([{ rule: 'high-error-rate', severity: 'WARNING', pipeline: 'EVENTS', metric: 'error_rate',
      value: 0.1, comparator: 'gt', threshold: 0.05, window: '1h', epochMillis: 1, message: 'm' }]);
    expect(received[0].rule).toBe('high-error-rate');
  });

  it('GET /alerts/rules for rules()', () => {
    svc.rules().subscribe();
    httpMock.expectOne(`${base}/alerts/rules`).flush([]);
  });

  it('POST /alerts/evaluate for evaluate()', () => {
    svc.evaluate().subscribe();
    const req = httpMock.expectOne(`${base}/alerts/evaluate`);
    expect(req.request.method).toBe('POST');
    req.flush([]);
  });
});
