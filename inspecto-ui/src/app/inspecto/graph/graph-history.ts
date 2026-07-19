/**
 * A generic, framework-free undo/redo snapshot-stack (Phase G). Deliberately NOT a command pattern:
 * the presentation state it targets (display options, layout, filters, collapsed branches) is small
 * and plainly serializable, so pushing/popping whole snapshots is simpler than per-action do/undo
 * closures — reach for a command pattern only if per-action granularity is ever actually needed.
 */
export interface HistoryStack<T> {
    readonly past: readonly T[];
    readonly future: readonly T[];
}

export function emptyHistory<T>(): HistoryStack<T> {
    return { past: [], future: [] };
}

/** Record `before` as the undo target for whatever mutation is about to happen; clears redo. */
export function pushHistory<T>(h: HistoryStack<T>, before: T): HistoryStack<T> {
    return { past: [...h.past, before], future: [] };
}

/** Pop the most recent snapshot; `current` is pushed onto redo so the mutation can be replayed. */
export function undo<T>(h: HistoryStack<T>, current: T): { history: HistoryStack<T>; state: T } | null {
    if (!h.past.length) return null;
    const state = h.past[h.past.length - 1];
    return { history: { past: h.past.slice(0, -1), future: [current, ...h.future] }, state };
}

/** Pop the most recently undone snapshot; `current` is pushed back onto undo. */
export function redo<T>(h: HistoryStack<T>, current: T): { history: HistoryStack<T>; state: T } | null {
    if (!h.future.length) return null;
    const state = h.future[0];
    return { history: { past: [...h.past, current], future: h.future.slice(1) }, state };
}
