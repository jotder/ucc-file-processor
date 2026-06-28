import type { ColumnMeta } from 'app/inspecto/query';
import type { Component } from './component-types';

/** A bounded sample of a component's output. */
export interface ComponentPreview {
    columns: string[];
    rows: Record<string, unknown>[];
    rowCount: number;
}

/**
 * The backend-agnostic seam: how a kind resolves columns / preview / listing without knowing mock-vs-server.
 * Mock now (the components mock / in-memory rows), DuckDB later — no call-site churn. This is the
 * QuerySpec→SQL boundary generalized to every kind.
 */
export interface DataProvider {
    columns(ref: { kind: string; id: string }): Promise<ColumnMeta[]>;
    preview(ref: { kind: string; id: string }, limit?: number): Promise<ComponentPreview>;
    list(kind: string): Promise<Component[]>;
}
