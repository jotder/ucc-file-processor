import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { RequirementsService } from './requirements.service';
import { buildRequirement } from './requirement-types';

function setup() {
    const create = vi.fn((_t: string, c: Record<string, unknown>) =>
        of({ type: 'requirement', name: String(c['id']), ref: `requirement/${c['id']}`, content: c }),
    );
    const update = vi.fn((_t: string, id: string, c: Record<string, unknown>) =>
        of({ type: 'requirement', name: id, ref: `requirement/${id}`, content: c }),
    );
    const list = vi.fn(() =>
        of([
            {
                type: 'requirement',
                name: 'daily_churn_kpi_ab12',
                ref: 'requirement/daily_churn_kpi_ab12',
                content: { title: 'Daily churn KPI', kind: 'kpi', description: 'x', status: 'submitted', submittedAt: '2026-07-03T00:00:00Z' },
            },
        ]),
    );
    TestBed.configureTestingModule({
        providers: [RequirementsService, { provide: ComponentsService, useValue: { create, update, list } }],
    });
    return { svc: TestBed.inject(RequirementsService), create, update, list };
}

describe('RequirementsService', () => {
    it('creates a requirement as a "requirement" registry component', () => {
        const { svc, create } = setup();
        let saved: { id: string } | undefined;
        svc.create(buildRequirement('x', 'kpi', 'y')).subscribe((r) => (saved = r));
        expect(create).toHaveBeenCalledWith('requirement', expect.objectContaining({ kind: 'kpi', status: 'submitted' }));
        expect(saved?.id).toBeTruthy();
    });

    it('saves (updates) an existing requirement via PUT', () => {
        const { svc, update } = setup();
        const r = { ...buildRequirement('x', 'kpi', 'y'), status: 'accepted' as const };
        svc.save(r).subscribe();
        expect(update).toHaveBeenCalledWith('requirement', r.id, expect.objectContaining({ status: 'accepted' }));
    });

    it('lists requirements back from the registry', () => {
        const { svc } = setup();
        let reqs: { id: string; title: string; status: string }[] = [];
        svc.list().subscribe((r) => (reqs = r));
        expect(reqs[0].id).toBe('daily_churn_kpi_ab12');
        expect(reqs[0].title).toBe('Daily churn KPI');
        expect(reqs[0].status).toBe('submitted');
    });
});
