import { describe, expect, it } from 'vitest';
import { MOCK_STORE_KEY, MockStore } from './mock-store';
import { memoryStorageAdapter } from './storage';

interface Widget {
    name: string;
    datasetRef?: string;
}

describe('MockStore', () => {
    it('does CRUD per space and collection', () => {
        const store = new MockStore();
        store.put('default', 'dataset', 'cdr', { name: 'cdr' });
        expect(store.get<{ name: string }>('default', 'dataset', 'cdr')?.name).toBe('cdr');
        expect(store.list('default', 'dataset')).toHaveLength(1);
        expect(store.list('other-space', 'dataset')).toHaveLength(0); // space isolation
        expect(store.delete('default', 'dataset', 'cdr')).toBe(true);
        expect(store.delete('default', 'dataset', 'cdr')).toBe(false);
        expect(store.list('default', 'dataset')).toHaveLength(0);
    });

    it('persists mutations and reloads them in a new instance', () => {
        const disk = memoryStorageAdapter();
        new MockStore(disk).put('default', 'job', 'nightly', { name: 'nightly' });
        const reloaded = new MockStore(disk);
        expect(reloaded.get<{ name: string }>('default', 'job', 'nightly')?.name).toBe('nightly');
    });

    it('discards a corrupt snapshot instead of failing', () => {
        const disk = memoryStorageAdapter();
        disk.set(MOCK_STORE_KEY, '{not json');
        const store = new MockStore(disk);
        expect(store.list('default', 'job')).toEqual([]);
        expect(disk.get(MOCK_STORE_KEY)).toBeNull(); // corrupt entry removed
    });

    it('seeds a space exactly once, including across reloads', () => {
        const disk = memoryStorageAdapter();
        let calls = 0;
        const seed = (s: MockStore, space: string): void => {
            calls++;
            s.put(space, 'dataset', 'seeded', { name: 'seeded' });
        };
        const store = new MockStore(disk);
        store.ensureSeeded('default', seed);
        store.ensureSeeded('default', seed);
        expect(calls).toBe(1);
        const reloaded = new MockStore(disk); // persisted data present ⇒ still no re-seed
        reloaded.ensureSeeded('default', seed);
        expect(calls).toBe(1);
        expect(reloaded.get<{ name: string }>('default', 'dataset', 'seeded')?.name).toBe('seeded');
    });

    it('reports referencers so deletes can 409 like the real backend', () => {
        const store = new MockStore();
        store.addRefRule({
            from: 'widget',
            refs: (e) => {
                const w = e as Widget;
                return w.datasetRef ? [{ collection: 'dataset', id: w.datasetRef }] : [];
            },
        });
        store.put('default', 'dataset', 'cdr', { name: 'cdr' });
        store.put('default', 'widget', 'calls-by-day', { name: 'calls-by-day', datasetRef: 'cdr' });
        store.put('default', 'widget', 'unbound', { name: 'unbound' });
        expect(store.referencesTo('default', 'dataset', 'cdr')).toEqual([
            { collection: 'widget', id: 'calls-by-day' },
        ]);
        expect(store.referencesTo('default', 'dataset', 'other')).toEqual([]);
        // References are space-scoped too.
        expect(store.referencesTo('space2', 'dataset', 'cdr')).toEqual([]);
    });

    it('reset drops memory and disk so spaces re-seed', () => {
        const disk = memoryStorageAdapter();
        const store = new MockStore(disk);
        store.ensureSeeded('default', (s, sp) => s.put(sp, 'dataset', 'a', { name: 'a' }));
        store.reset();
        expect(disk.get(MOCK_STORE_KEY)).toBeNull();
        let reseeded = false;
        store.ensureSeeded('default', () => (reseeded = true));
        expect(reseeded).toBe(true);
    });
});
