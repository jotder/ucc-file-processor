import { describe, expect, it } from 'vitest';
import { byTier } from 'app/inspecto/component-model';
import { modulePropFor, parserTypeDef, propsFor, PARSER_TYPES, sampleFor, toAttributeSpecs } from './parser-types';

describe('parser-types tier audit', () => {
    it('classifies every property of every type into a tier (nothing left unclassified)', () => {
        for (const t of PARSER_TYPES) {
            for (const p of propsFor(t.type)) {
                expect(['required', 'optional', 'advanced']).toContain(p.tier);
            }
        }
    });

    it('DSV: delimiter + header are required; encoding is optional; whitespace trimming is advanced', () => {
        const byKey = new Map(propsFor('dsv').map((p) => [p.key, p]));
        expect(byKey.get('column_delimiter')?.tier).toBe('required');
        expect(byKey.get('header_position')?.tier).toBe('required');
        expect(byKey.get('encoding')?.tier).toBe('optional');
        expect(byKey.get('trim_whitespace')?.tier).toBe('advanced');
    });

    it('the shared Sampling knobs are always advanced, on every type', () => {
        for (const t of PARSER_TYPES) {
            const props = propsFor(t.type);
            for (const key of ['sample_rows', 'default_column_length', 'count_length_in_bytes']) {
                expect(props.find((p) => p.key === key)?.tier).toBe('advanced');
            }
        }
    });

    it('ASN.1 excludes the module property from toAttributeSpecs but keeps it discoverable via modulePropFor', () => {
        const specs = toAttributeSpecs('asn1');
        expect(specs.some((s) => s.key === 'schema_spec')).toBe(false);
        expect(specs.some((s) => s.key === 'encoding_rules')).toBe(true);
        expect(modulePropFor('asn1')?.key).toBe('schema_spec');
        expect(modulePropFor('dsv')).toBeUndefined();
    });

    it('TXT: record_length depends on frontend=fixedwidth', () => {
        const specs = toAttributeSpecs('txt');
        const recordLength = specs.find((s) => s.key === 'record_length');
        expect(recordLength?.dependsOn).toEqual({ key: 'frontend', equals: 'fixedwidth' });
    });

    it('groups a real type into required/optional/advanced via the shared byTier helper', () => {
        const grouped = byTier(toAttributeSpecs('json'));
        expect(grouped.required.map((s) => s.key)).toEqual(['mode', 'root_path']);
        expect(grouped.optional.map((s) => s.key)).toEqual(['extension', 'encoding']);
        expect(grouped.advanced.map((s) => s.key)).toEqual(['sample_rows', 'default_column_length', 'count_length_in_bytes']);
    });

    it('maps select options and control types onto AttributeSpec', () => {
        const headerPos = toAttributeSpecs('html').find((s) => s.key === 'header_position');
        expect(headerPos?.type).toBe('select');
        expect(headerPos?.options).toEqual([{ value: 'top', label: 'top' }, { value: 'none', label: 'none' }]);
        expect(toAttributeSpecs('other').find((s) => s.key === 'plugin_config')?.type).toBe('multiline');
        expect(toAttributeSpecs('asn1').find((s) => s.key === 'decode_implicit')?.type).toBe('boolean');
    });

    it('falls back to DSV for an unknown or blank type', () => {
        expect(parserTypeDef('nonsense').type).toBe('dsv');
        expect(parserTypeDef(undefined).type).toBe('dsv');
        expect(sampleFor('nonsense')).toBe(sampleFor('dsv'));
    });
});
