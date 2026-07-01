import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuthoredPipeline } from 'app/inspecto/api';
import { allKinds, clearKinds, getKind } from 'app/inspecto/component-model';
import { PIPELINE_KIND, PLATFORM_KIND_IDS, registerPlatformKinds } from './platform-kinds';

describe('platform kinds (P2 adapters)', () => {
    beforeEach(() => {
        clearKinds();
        registerPlatformKinds();
    });
    afterEach(() => clearKinds());

    it('registers every existing platform kind on the model', () => {
        expect(allKinds().map((k) => k.id)).toEqual(expect.arrayContaining(PLATFORM_KIND_IDS));
    });

    it('atomic kinds carry no parts, a none wiring, and a no-op validator', () => {
        const grammar = getKind('grammar')!;
        expect(grammar.wiring).toBe('none');
        expect(grammar.allowedPartKinds).toEqual([]);
        expect(grammar.config.validate({})).toEqual([]);
    });

    it('pipeline is a composite graph kind whose wiring derives from the authored flow DAG', () => {
        expect(PIPELINE_KIND.wiring).toBe('graph');
        expect(PIPELINE_KIND.allowedPartKinds).toContain('grammar');
        const flow: AuthoredPipeline = {
            name: 'p1',
            active: false,
            nodes: [{ id: 'src', type: 'collector' }, { id: 'parse', type: 'dsv' }],
            edges: [{ from: 'src', to: 'parse', rel: 'data' }],
        };
        const parts = [
            { partId: 'src', ref: { kind: 'grammar' } },
            { partId: 'parse', ref: { kind: 'grammar', id: 'cdr' } },
        ];
        expect(PIPELINE_KIND.deriveWiring!(parts, flow)).toEqual({
            strategy: 'graph',
            nodes: [{ partId: 'src' }, { partId: 'parse' }],
            edges: [{ from: 'src', to: 'parse', rel: 'data' }],
        });
    });

    it('registerPlatformKinds is idempotent (guarded against the duplicate-id throw)', () => {
        expect(() => registerPlatformKinds()).not.toThrow();
    });
});
