import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { LineageService, StoreLineage } from './lineage.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

describe('LineageService', () => {
    let svc: LineageService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [LineageService, provideHttpClient(), provideHttpClientTesting()],
        });
        svc = TestBed.inject(LineageService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('lineage(store) GETs /lineage?store= and returns the stitch', () => {
        const payload: StoreLineage = {
            store: 'events_raw',
            upstream: [{ pipeline: 'events_etl', batchId: 'b1', inputFile: 'a.csv', partition: 'day=2020-04-03', rowCount: 1234 }],
            downstream: [{ flow: 'events_rollup', sinks: ['events_daily'] }],
        };
        let got: StoreLineage | undefined;
        svc.lineage('events_raw').subscribe((d) => (got = d));
        httpMock.expectOne(`${base}/lineage?store=events_raw`).flush(payload);
        expect(got).toEqual(payload);
    });
});
