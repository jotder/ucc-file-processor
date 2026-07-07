import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it } from 'vitest';
import { apiUrl } from 'app/inspecto/api';
import { RequirementsService } from './requirements.service';
import { buildRequirement } from './requirement-types';

function setup() {
    TestBed.configureTestingModule({
        providers: [RequirementsService, provideHttpClient(), provideHttpClientTesting()],
    });
    return { svc: TestBed.inject(RequirementsService), http: TestBed.inject(HttpTestingController) };
}

describe('RequirementsService', () => {
    it('submits a requirement via POST /requirements', () => {
        const { svc, http } = setup();
        const r = buildRequirement('x', 'kpi', 'y');
        svc.create(r).subscribe();
        const req = http.expectOne(apiUrl('/requirements'));
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toMatchObject({ id: r.id, kind: 'kpi', title: 'x' });
        req.flush({ ...r, status: 'submitted' });
        http.verify();
    });

    it('decides via POST /requirements/{id}/decision', () => {
        const { svc, http } = setup();
        svc.decide('churn_kpi', true, 'looks good').subscribe();
        const req = http.expectOne(apiUrl('/requirements/churn_kpi/decision'));
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ accept: true, note: 'looks good' });
        req.flush({ id: 'churn_kpi', status: 'accepted' });
        http.verify();
    });

    it('delivers via POST /requirements/{id}/deliver', () => {
        const { svc, http } = setup();
        svc.deliver('churn_kpi', 'dashboard/churn').subscribe();
        const req = http.expectOne(apiUrl('/requirements/churn_kpi/deliver'));
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ note: 'dashboard/churn' });
        req.flush({ id: 'churn_kpi', status: 'delivered' });
        http.verify();
    });

    it('lists requirements via GET /requirements', () => {
        const { svc, http } = setup();
        let reqs: { id: string; title: string; status: string }[] = [];
        svc.list().subscribe((r) => (reqs = r));
        http.expectOne(apiUrl('/requirements')).flush([
            { id: 'daily_churn_kpi_ab12', title: 'Daily churn KPI', kind: 'kpi', description: 'x', status: 'submitted', submittedAt: '2026-07-03T00:00:00Z' },
        ]);
        expect(reqs[0].id).toBe('daily_churn_kpi_ab12');
        expect(reqs[0].status).toBe('submitted');
        http.verify();
    });
});
