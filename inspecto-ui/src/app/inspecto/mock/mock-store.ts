import { StorageAdapter } from './storage';

/**
 * The unified stateful mock store (Wave 0, W1 — see docs/superpower/frontend-review-and-completion-plan.md).
 *
 * One per-Space entity store backing EVERY mock domain handler, replacing the per-feature in-memory
 * `STORE` constants that were scattered across the mock interceptors. Properties the old mocks lacked:
 *
 * - **Persistence** — every mutation snapshots to the injected {@link StorageAdapter} (localStorage in
 *   the app), so authored mock data survives a reload. `reset()` restores pristine seeds.
 * - **Per-Space keying** — collections are scoped by space id, so the multi-space UX is real in mock mode.
 * - **Referential integrity** — {@link RefRule}s let a delete report what still references the entity,
 *   mirroring the real backend's 409 behavior instead of silently orphaning bindings.
 * - **Seeding** — `ensureSeeded` runs a seed pack once per space (Space-Template seed packs plug in here).
 *
 * Framework-free (no Angular imports) — unit-tests in plain vitest like the component-model/query cores.
 */

/** One outgoing-reference extractor: how entities of `from` point at other entities. */
export interface RefRule {
    /** The collection whose entities hold references. */
    from: string;
    /** Extract the outgoing refs of one entity (absent/None ⇒ no refs). */
    refs(entity: unknown): Array<{ collection: string; id: string }>;
}

/** One entity that still references a delete target — the mock analogue of the backend's 409 payload. */
export interface Referencer {
    collection: string;
    id: string;
}

type SpaceData = Record<string, Record<string, unknown>>; // collection → id → entity
type StoreData = Record<string, SpaceData>; // space → collections

/** Bump when the persisted shape or the seed contract changes — old snapshots are then discarded. */
export const MOCK_STORE_KEY = 'inspecto.mock.v16'; // v16: tag registry + Tag Rules (v15: incident mail lifecycle seeds)

export class MockStore {
    private data: StoreData = {};
    private rules: RefRule[] = [];

    constructor(
        private readonly storage: StorageAdapter | null = null,
        private readonly key: string = MOCK_STORE_KEY,
    ) {
        this.load();
    }

    /** Seed a space exactly once (no-op if the space already has data — persisted or from this session). */
    ensureSeeded(space: string, seed: (store: MockStore, space: string) => void): void {
        if (this.data[space]) return;
        this.data[space] = {};
        seed(this, space);
        this.persist();
    }

    list<T>(space: string, collection: string): T[] {
        return Object.values(this.collection(space, collection)) as T[];
    }

    /** `[id, entity]` pairs — for callers that need the storage key (e.g. trimming by age). */
    entries<T>(space: string, collection: string): Array<[string, T]> {
        return Object.entries(this.collection(space, collection)) as Array<[string, T]>;
    }

    get<T>(space: string, collection: string, id: string): T | undefined {
        return this.collection(space, collection)[id] as T | undefined;
    }

    has(space: string, collection: string, id: string): boolean {
        return id in this.collection(space, collection);
    }

    put<T>(space: string, collection: string, id: string, entity: T): T {
        this.collection(space, collection)[id] = entity;
        this.persist();
        return entity;
    }

    delete(space: string, collection: string, id: string): boolean {
        const coll = this.collection(space, collection);
        const existed = id in coll;
        delete coll[id];
        if (existed) this.persist();
        return existed;
    }

    /** Register an outgoing-reference extractor used by {@link referencesTo}. */
    addRefRule(rule: RefRule): void {
        this.rules.push(rule);
    }

    /** Everything in `space` that still references `<collection>/<id>` — non-empty ⇒ a delete should 409. */
    referencesTo(space: string, collection: string, id: string): Referencer[] {
        const hits: Referencer[] = [];
        for (const rule of this.rules) {
            for (const [refId, entity] of Object.entries(this.collection(space, rule.from))) {
                if (rule.refs(entity).some((r) => r.collection === collection && r.id === id)) {
                    hits.push({ collection: rule.from, id: refId });
                }
            }
        }
        return hits;
    }

    /** Drop one space's data (it re-seeds on the next `ensureSeeded`). */
    clearSpace(space: string): void {
        delete this.data[space];
        this.persist();
    }

    /** Drop everything — memory and the persisted snapshot. All spaces re-seed on next use. */
    reset(): void {
        this.data = {};
        this.storage?.remove(this.key);
    }

    private collection(space: string, collection: string): Record<string, unknown> {
        const s = (this.data[space] ??= {});
        return (s[collection] ??= {});
    }

    private persist(): void {
        try {
            this.storage?.set(this.key, JSON.stringify(this.data));
        } catch {
            // Quota/serialization failures degrade to in-memory-only — never break the request path.
        }
    }

    private load(): void {
        const raw = this.storage?.get(this.key);
        if (!raw) return;
        try {
            const parsed: unknown = JSON.parse(raw);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                this.data = parsed as StoreData;
            }
        } catch {
            this.storage?.remove(this.key); // corrupt snapshot — discard, re-seed
        }
    }
}
