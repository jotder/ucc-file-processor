import { Observable, Subscription } from 'rxjs';

/**
 * Standard optimistic-UI mutation pattern for Inspecto.
 *
 * Apply the expected result to local state *immediately*, fire the server call, and roll back if it
 * fails. Success is silent (the UI already shows the result) — only failures surface, via `rollback`
 * + `onError`. Use for reversible, low-risk mutations (toggles, status flips, simple edits) where
 * snappy feedback matters; keep the request→refetch flow for create/destroy or anything where the
 * server computes a non-obvious result.
 *
 * @example
 * const prev = item.enabled;
 * optimisticMutate({
 *   apply:    () => { item.enabled = !prev; this.rows = [...this.rows]; }, // reassign so the grid re-renders
 *   commit:   this.api.toggle(item.id),
 *   reconcile:(r) => { item.enabled = r.enabled; this.rows = [...this.rows]; }, // sync with server truth
 *   rollback: () => { item.enabled = prev; this.rows = [...this.rows]; },
 *   onError:  (e) => this.toastr.error(apiErrorMessage(e, 'Toggle failed')),
 * });
 */
export interface OptimisticMutation<R = unknown, E = unknown> {
    /** Mutate local state to the expected post-success value. Runs synchronously, before the call. */
    apply: () => void;
    /** The server mutation. */
    commit: Observable<R>;
    /** Optional: reconcile local state with the authoritative server result on success. */
    reconcile?: (result: R) => void;
    /** Restore the previous local state when the call fails. */
    rollback: () => void;
    /** Optional: surface the error (e.g. a toast). Called after {@link rollback}. */
    onError?: (err: E) => void;
}

/** Run an {@link OptimisticMutation}. Returns the Subscription so callers can cancel if needed. */
export function optimisticMutate<R, E = unknown>(m: OptimisticMutation<R, E>): Subscription {
    m.apply();
    return m.commit.subscribe({
        next: (result) => m.reconcile?.(result),
        error: (err: E) => {
            m.rollback();
            m.onError?.(err);
        },
    });
}
