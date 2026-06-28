import { describe, expect, it } from 'vitest';
import { getKind } from 'app/inspecto/component-model';
import { DATASET_KIND, validateDatasetConfig } from './dataset.kind';

describe('dataset ComponentKind', () => {
    it('registers itself on import so Studio is built on the model', () => {
        expect(getKind('dataset')).toBe(DATASET_KIND);
        expect(DATASET_KIND.wiring).toBe('none');
        expect(DATASET_KIND.allowedPartKinds).toEqual([]);
    });

    it('create() yields a valid empty virtual config', () => {
        const cfg = DATASET_KIND.config.create!();
        expect(cfg.kind).toBe('virtual');
        // empty virtual has no query yet, so validation flags it — but the source is set
        expect(validateDatasetConfig(cfg).some((f) => f.path === 'sourceName')).toBe(false);
    });

    it('flags a missing source as an error', () => {
        const findings = validateDatasetConfig({ kind: 'virtual', query: {} });
        expect(findings.some((f) => f.severity === 'error' && f.path === 'sourceName')).toBe(true);
    });

    it('flags a virtual dataset with no query', () => {
        const findings = validateDatasetConfig({ kind: 'virtual', sourceName: 'cdr' });
        expect(findings.some((f) => f.severity === 'error' && f.path === 'query')).toBe(true);
    });
});
