import { Injectable, signal } from '@angular/core';

interface SqlBucket {
    recent: string[];
    favorites: string[];
}

const KEY = 'inspecto.sqlHistory.v1';
const MAX_RECENT = 25;

/**
 * Per-source SQL **history & favorites** for the Pro editor, persisted in `localStorage` so they survive a
 * reload (offline — no backend). History is appended only on a *successful* run; favorites are toggled
 * explicitly. State is held in a signal, so the editor's menus recompute reactively.
 */
@Injectable({ providedIn: 'root' })
export class SqlHistoryService {
    private readonly store = signal<Record<string, SqlBucket>>(this.load());

    private load(): Record<string, SqlBucket> {
        try {
            const raw = localStorage.getItem(KEY);
            return raw ? (JSON.parse(raw) as Record<string, SqlBucket>) : {};
        } catch {
            return {};
        }
    }

    private persist(next: Record<string, SqlBucket>): void {
        this.store.set(next);
        try {
            localStorage.setItem(KEY, JSON.stringify(next));
        } catch {
            /* quota exceeded / storage disabled — keep the in-memory copy */
        }
    }

    private bucket(source: string): SqlBucket {
        return this.store()[source] ?? { recent: [], favorites: [] };
    }

    recent(source: string): string[] {
        return this.bucket(source).recent;
    }
    favorites(source: string): string[] {
        return this.bucket(source).favorites;
    }
    isFavorite(source: string, sql: string): boolean {
        return this.bucket(source).favorites.includes(sql.trim());
    }

    /** Record a successfully-run query (most-recent first, de-duplicated, capped). */
    addRun(source: string, sql: string): void {
        const q = sql.trim();
        if (!q) return;
        const b = this.bucket(source);
        const recent = [q, ...b.recent.filter((s) => s !== q)].slice(0, MAX_RECENT);
        this.persist({ ...this.store(), [source]: { ...b, recent } });
    }

    toggleFavorite(source: string, sql: string): void {
        const q = sql.trim();
        if (!q) return;
        const b = this.bucket(source);
        const favorites = b.favorites.includes(q) ? b.favorites.filter((s) => s !== q) : [q, ...b.favorites];
        this.persist({ ...this.store(), [source]: { ...b, favorites } });
    }
}
