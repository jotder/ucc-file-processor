import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PipelinesService } from './pipelines.service';
import { InboxStatus } from './models';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl; // '' in prod, '/api' behind the dev proxy

describe('PipelinesService', () => {
  let svc: PipelinesService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PipelinesService, provideHttpClient(), provideHttpClientTesting()],
    });
    svc = TestBed.inject(PipelinesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GET /pipelines for list()', () => {
    svc.list().subscribe();
    httpMock.expectOne((r) => r.method === 'GET' && r.url === `${base}/pipelines`).flush([]);
  });

  it('GET /pipelines/{n}/pending and returns the InboxStatus', () => {
    const body: InboxStatus = {
      pipeline: 'mini_etl',
      inbox: '/in',
      pending: 3,
      running: true,
      current: { batchId: 'B1', file: 'a.csv.gz', index: 2, total: 5, startedAt: '2026-06-12 10:00:00' },
    };
    let received: InboxStatus | undefined;
    svc.pending('mini_etl').subscribe((s) => (received = s));
    const req = httpMock.expectOne(`${base}/pipelines/mini_etl/pending`);
    expect(req.request.method).toBe('GET');
    req.flush(body);
    expect(received).toEqual(body);
  });

  it('URL-encodes the pipeline name in the path', () => {
    svc.trigger('a/b name').subscribe();
    httpMock.expectOne(`${base}/pipelines/a%2Fb%20name/trigger`).flush({ total: 1, failed: 0 });
  });

  it('omits an undefined batchId from lineage query params', () => {
    svc.lineage('mini_etl').subscribe();
    const req = httpMock.expectOne((r) => r.url === `${base}/pipelines/mini_etl/lineage`);
    expect(req.request.params.has('batchId')).toBe(false);
    req.flush([]);
  });

  it('includes batchId when provided', () => {
    svc.lineage('mini_etl', 'b1').subscribe();
    const req = httpMock.expectOne((r) => r.url === `${base}/pipelines/mini_etl/lineage`);
    expect(req.request.params.get('batchId')).toBe('b1');
    req.flush([]);
  });
});
