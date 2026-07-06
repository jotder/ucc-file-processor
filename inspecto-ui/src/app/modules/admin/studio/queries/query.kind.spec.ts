import { describe, expect, it } from 'vitest';
import { getKind } from 'app/inspecto/component-model';
import { QUERY_KIND, validateQueryConfig } from './query.kind';

describe('query ComponentKind (R3)', () => {
    it('registers itself on import, atomic with no parts', () => {
        expect(getKind('query')).toBe(QUERY_KIND);
        expect(QUERY_KIND.wiring).toBe('none');
        expect(QUERY_KIND.allowedPartKinds).toEqual([]);
        expect(QUERY_KIND.exec?.runnerKey).toBe('query');
    });

    it('derives its one binds edge to the source dataset', () => {
        expect(QUERY_KIND.deriveRefs!({ type: 'sql', datasetId: 'cdr_sample', text: 'SELECT 1', parameters: [] })).toEqual([
            { kind: 'dataset', id: 'cdr_sample', rel: 'binds', via: 'dataset' },
        ]);
    });

    it('flags a missing source dataset', () => {
        expect(validateQueryConfig({ type: 'sql', text: 'SELECT 1' }).some((f) => f.severity === 'error' && f.path === 'datasetId')).toBe(true);
    });

    it('flags a SQL query with no text, and a structured query with no model', () => {
        expect(validateQueryConfig({ type: 'sql', datasetId: 'd', text: '  ' }).some((f) => f.path === 'text')).toBe(true);
        expect(validateQueryConfig({ type: 'structured', datasetId: 'd', model: null }).some((f) => f.path === 'model')).toBe(true);
    });

    it('create() yields an empty SQL query', () => {
        const cfg = QUERY_KIND.config.create!();
        expect(cfg.type).toBe('sql');
        expect(cfg.parameters).toEqual([]);
    });
});
