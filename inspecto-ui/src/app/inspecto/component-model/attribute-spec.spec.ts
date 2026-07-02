import { describe, expect, it } from 'vitest';
import {
    AttributeSpec,
    attributeValidator,
    byTier,
    defaultsFor,
    validateAttributes,
    visibleSpecs,
} from './attribute-spec';

const SPECS: AttributeSpec[] = [
    { key: 'name', label: 'Name', type: 'identifier', tier: 'required' },
    { key: 'type', label: 'Type', type: 'select', tier: 'required', options: [{ value: 'enrich', label: 'Enrich' }, { value: 'report', label: 'Report' }] },
    { key: 'cron', label: 'Cron', type: 'string', tier: 'optional', pattern: '[0-9*/ ,-]+', dependsOn: { key: 'type', equals: 'report' } },
    { key: 'threads', label: 'Threads', type: 'number', tier: 'advanced', default: 4, min: 1, max: 64 },
    { key: 'enabled', label: 'Enabled', type: 'boolean', tier: 'optional', default: true },
];

describe('attribute-spec', () => {
    it('collects declared defaults', () => {
        expect(defaultsFor(SPECS)).toEqual({ threads: 4, enabled: true });
    });

    it('groups by tier in declaration order', () => {
        const t = byTier(SPECS);
        expect(t.required.map((s) => s.key)).toEqual(['name', 'type']);
        expect(t.optional.map((s) => s.key)).toEqual(['cron', 'enabled']);
        expect(t.advanced.map((s) => s.key)).toEqual(['threads']);
    });

    it('hides dependsOn attributes until their controller matches — and skips their validation', () => {
        expect(visibleSpecs(SPECS, { type: 'enrich' }).map((s) => s.key)).not.toContain('cron');
        expect(visibleSpecs(SPECS, { type: 'report' }).map((s) => s.key)).toContain('cron');
        // invalid cron, but hidden ⇒ no finding
        const hidden = validateAttributes(SPECS, { name: 'x', type: 'enrich', cron: '!!!' });
        expect(hidden).toEqual([]);
        const shown = validateAttributes(SPECS, { name: 'x', type: 'report', cron: '!!!' });
        expect(shown.map((f) => f.path)).toEqual(['cron']);
    });

    it('flags missing required, bad select, bad identifier, out-of-range number', () => {
        const findings = validateAttributes(SPECS, { name: '0bad', type: 'nope', threads: 128 });
        expect(findings.map((f) => f.path).sort()).toEqual(['name', 'threads', 'type']);
        expect(findings.every((f) => f.severity === 'error')).toBe(true);
        expect(validateAttributes(SPECS, {}).map((f) => f.path)).toEqual(['name', 'type']);
    });

    it('accepts a fully valid config and wraps as a kind validator', () => {
        const validate = attributeValidator(SPECS);
        expect(validate({ name: 'daily_kpi', type: 'report', cron: '0 2 * * *', threads: 8, enabled: false })).toEqual([]);
        expect(validate(null).map((f) => f.path)).toEqual(['name', 'type']);
    });
});
