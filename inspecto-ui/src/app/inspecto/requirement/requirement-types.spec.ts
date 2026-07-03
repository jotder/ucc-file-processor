import { describe, expect, it } from 'vitest';
import { buildRequirement, decideRequirement, deliverRequirement } from './requirement-types';

describe('requirement-types', () => {
    it('builds a submitted requirement with a slugged id', () => {
        const r = buildRequirement('Daily Churn KPI!!', 'kpi', 'Track churn by region daily.');
        expect(r.id).toMatch(/^daily_churn_kpi_[a-z0-9]{4}$/);
        expect(r.title).toBe('Daily Churn KPI!!');
        expect(r.kind).toBe('kpi');
        expect(r.status).toBe('submitted');
        expect(r.submittedAt).toBeTruthy();
    });

    it('falls back to a generic slug when the title has no alphanumerics', () => {
        const r = buildRequirement('!!!', 'report', 'x');
        expect(r.id).toMatch(/^requirement_[a-z0-9]{4}$/);
    });

    it('decides a requirement (accept), recording an optional note', () => {
        const r = buildRequirement('x', 'kpi', 'y');
        const decided = decideRequirement(r, true, 'looks good');
        expect(decided.status).toBe('accepted');
        expect(decided.decisionNote).toBe('looks good');
        expect(decided.decidedAt).toBeTruthy();
    });

    it('decides a requirement (reject) with no note', () => {
        const r = buildRequirement('x', 'kpi', 'y');
        const decided = decideRequirement(r, false);
        expect(decided.status).toBe('rejected');
        expect(decided.decisionNote).toBeUndefined();
    });

    it('delivers an accepted requirement', () => {
        const r = decideRequirement(buildRequirement('x', 'kpi', 'y'), true);
        const delivered = deliverRequirement(r, 'dashboard/churn_kpi');
        expect(delivered.status).toBe('delivered');
        expect(delivered.deliveredNote).toBe('dashboard/churn_kpi');
        expect(delivered.deliveredAt).toBeTruthy();
    });
});
