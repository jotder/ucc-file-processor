import { beforeEach, describe, expect, it } from 'vitest';
import { ComponentKind } from './component-kind';
import { allKinds, clearKinds, getKind, registerKind } from './component-registry';

const kind = (id: string): ComponentKind => ({
    id,
    label: id,
    allowedPartKinds: [],
    wiring: 'none',
    config: { validate: () => [] },
});

describe('component-registry', () => {
    beforeEach(() => clearKinds());

    it('registers and retrieves a kind', () => {
        registerKind(kind('chart'));
        expect(getKind('chart')?.label).toBe('chart');
        expect(allKinds().map((k) => k.id)).toEqual(['chart']);
    });

    it('throws on a duplicate id', () => {
        registerKind(kind('chart'));
        expect(() => registerKind(kind('chart'))).toThrow(/Duplicate/);
    });

    it('returns undefined for an unknown kind', () => {
        expect(getKind('nope')).toBeUndefined();
    });
});
