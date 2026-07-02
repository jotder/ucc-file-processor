/**
 * Storage seam for the unified mock store — framework-free so the store unit-tests in plain vitest
 * and the persistence backend stays swappable (localStorage in the app, memory in tests/jsdom).
 */
export interface StorageAdapter {
    get(key: string): string | null;
    set(key: string, value: string): void;
    remove(key: string): void;
}

/**
 * The browser's localStorage as a {@link StorageAdapter}, or `null` when unavailable/denied
 * (jsdom without storage, Safari private mode quota errors) — the store then runs in-memory only.
 */
export function localStorageAdapter(): StorageAdapter | null {
    try {
        const ls = globalThis.localStorage;
        if (!ls) return null;
        const probe = '__inspecto_mock_probe__';
        ls.setItem(probe, '1');
        ls.removeItem(probe);
        return {
            get: (k) => ls.getItem(k),
            set: (k, v) => ls.setItem(k, v),
            remove: (k) => ls.removeItem(k),
        };
    } catch {
        return null;
    }
}

/** An in-memory {@link StorageAdapter} for tests — lets two store instances share one "disk". */
export function memoryStorageAdapter(): StorageAdapter {
    const m = new Map<string, string>();
    return {
        get: (k) => m.get(k) ?? null,
        set: (k, v) => m.set(k, v),
        remove: (k) => m.delete(k),
    };
}
