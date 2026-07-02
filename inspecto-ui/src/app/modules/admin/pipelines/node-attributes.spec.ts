import { describe, expect, it } from 'vitest';
import { byTier } from 'app/inspecto/component-model';
import { nodeAttributesFor } from './node-attributes';

describe('node-attributes', () => {
    it('returns a tiered schema for a known node type', () => {
        const specs = nodeAttributesFor('sink.file');
        expect(specs).toBeDefined();
        const grouped = byTier(specs!);
        expect(grouped.required.map((s) => s.key)).toEqual(['format']);
        expect(grouped.optional.map((s) => s.key)).toEqual(['partition_by']);
        expect(grouped.advanced.map((s) => s.key)).toEqual(['compression']);
    });

    it('classifies every attribute of every known type into a tier', () => {
        for (const type of ['collector.file', 'collector.database', 'collector.stream', 'transform.filter', 'transform.route', 'transform.aggregate', 'transform.alert', 'sink.file', 'sink.database']) {
            for (const s of nodeAttributesFor(type)!) {
                expect(['required', 'optional', 'advanced']).toContain(s.tier);
            }
        }
    });

    it('carries a dependsOn on sink.database key columns (upsert only)', () => {
        const keyCols = nodeAttributesFor('sink.database')!.find((s) => s.key === 'key_columns');
        expect(keyCols?.dependsOn).toEqual({ key: 'mode', equals: 'upsert' });
    });

    it('returns undefined for an unknown / plugin / parser type (free-form fallback)', () => {
        expect(nodeAttributesFor('transform.record')).toBeUndefined();
        expect(nodeAttributesFor('parser.dsv')).toBeUndefined();
        expect(nodeAttributesFor('acme.custom')).toBeUndefined();
        expect(nodeAttributesFor(undefined)).toBeUndefined();
    });
});
