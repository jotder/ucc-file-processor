import { describe, expect, it } from 'vitest';
import { emptyGroup } from 'app/inspecto/query';
import { buildRuleTemplate } from './rule-types';
import { ConditionGroup } from 'app/inspecto/query';

describe('buildRuleTemplate', () => {
    it('round-trips a query model into a rule template', () => {
        const where: ConditionGroup = {
            kind: 'group',
            op: 'AND',
            items: [{ kind: 'condition', field: 'severity', operator: '=', value: 'CRITICAL' }],
        };
        const r = buildRuleTemplate('high_sev', 'alerts', { projection: ['rule', 'severity'], where, sqlOverride: null });
        expect(r).toEqual({
            id: 'high_sev',
            name: 'high_sev',
            source: 'alerts',
            projection: ['rule', 'severity'],
            where,
            sqlOverride: null,
        });
    });

    it('defaults sqlOverride to null', () => {
        const r = buildRuleTemplate('r', 's', { projection: '*', where: emptyGroup() });
        expect(r.sqlOverride).toBeNull();
    });
});
