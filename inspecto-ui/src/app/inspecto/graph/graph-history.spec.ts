import { describe, expect, it } from 'vitest';
import { emptyHistory, pushHistory, redo, undo } from './graph-history';

describe('graph-history (Phase G snapshot-stack)', () => {
    it('undo restores the last pushed snapshot and stages the current one for redo', () => {
        let h = emptyHistory<number>();
        h = pushHistory(h, 1); // about to move from state 1 to state 2
        const u = undo(h, 2);
        expect(u?.state).toBe(1);
        expect(u?.history.past).toEqual([]);
        expect(u?.history.future).toEqual([2]);
    });

    it('redo replays the most recently undone snapshot and stages current for undo again', () => {
        let h = emptyHistory<number>();
        h = pushHistory(h, 1);
        const u = undo(h, 2)!;
        const r = redo(u.history, u.state);
        expect(r?.state).toBe(2);
        expect(r?.history.past).toEqual([1]);
        expect(r?.history.future).toEqual([]);
    });

    it('a new push after an undo clears redo (branching history, no redo of a stale future)', () => {
        let h = emptyHistory<number>();
        h = pushHistory(h, 1);
        const u = undo(h, 2)!;
        h = pushHistory(u.history, 1); // a fresh mutation from state 1
        expect(h.future).toEqual([]);
    });

    it('undo/redo on an empty stack is a no-op (returns null)', () => {
        expect(undo(emptyHistory<number>(), 1)).toBeNull();
        expect(redo(emptyHistory<number>(), 1)).toBeNull();
    });

    it('multiple undos walk back through the whole stack in order', () => {
        let h = emptyHistory<number>();
        h = pushHistory(h, 1);
        h = pushHistory(h, 2);
        h = pushHistory(h, 3);
        const u1 = undo(h, 4)!;
        expect(u1.state).toBe(3);
        const u2 = undo(u1.history, u1.state)!;
        expect(u2.state).toBe(2);
        const u3 = undo(u2.history, u2.state)!;
        expect(u3.state).toBe(1);
        expect(undo(u3.history, u3.state)).toBeNull();
    });
});
