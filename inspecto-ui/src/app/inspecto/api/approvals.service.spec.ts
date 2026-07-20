import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AgentApproval, ApprovalsService } from './approvals.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

const PENDING: AgentApproval = {
    id: 'a1',
    tool: 'job_run',
    agentActor: 'agent:run-1',
    summary: 'run job nightly-rollup',
    arguments: { job: 'nightly-rollup' },
    preview: { action: 'run-job', target: 'nightly-rollup', jobExists: true },
    status: 'PENDING',
    requestedAt: '2026-07-20T10:00:00Z',
    decidedAt: null,
    decidedBy: null,
};

describe('ApprovalsService (AGT-5 P3 approvals inbox)', () => {
    let svc: ApprovalsService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [ApprovalsService, provideHttpClient(), provideHttpClientTesting()],
        });
        svc = TestBed.inject(ApprovalsService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('GET /agent/approvals unwraps {approvals} and passes the limit', () => {
        let received: AgentApproval[] = [];
        svc.list(10).subscribe((a) => (received = a));
        const req = httpMock.expectOne((r) => r.method === 'GET' && r.url === `${base}/agent/approvals`);
        expect(req.request.params.get('limit')).toBe('10');
        req.flush({ approvals: [PENDING] });
        expect(received[0].id).toBe('a1');
    });

    it('list() tolerates a body with no approvals field (act tier off)', () => {
        let received: AgentApproval[] = [PENDING];
        svc.list().subscribe((a) => (received = a));
        httpMock.expectOne((r) => r.url === `${base}/agent/approvals`).flush({});
        expect(received).toEqual([]);
    });

    it('GET /agent/approvals/{id} for get()', () => {
        svc.get('a1').subscribe();
        httpMock.expectOne(`${base}/agent/approvals/a1`).flush(PENDING);
    });

    it('POST /agent/approvals/{id}/decision carries decision + decidedBy', () => {
        svc.decide('a1', 'approve', 'alice').subscribe();
        const req = httpMock.expectOne(`${base}/agent/approvals/a1/decision`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ decision: 'approve', decidedBy: 'alice' });
        req.flush({ ...PENDING, status: 'APPROVED', decidedBy: 'alice', decidedAt: '2026-07-20T10:01:00Z' });
    });
});
