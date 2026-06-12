import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Subscription } from 'rxjs';
import { visibleInterval } from './auto-refresh';

/** Override the read-only document.hidden getter, then fire visibilitychange. */
function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('visibleInterval', () => {
  let sub: Subscription | undefined;

  beforeEach(() => {
    vi.useFakeTimers();
    setHidden(false);
  });

  afterEach(() => {
    sub?.unsubscribe();
    vi.useRealTimers();
    setHidden(false);
  });

  it('does not emit before the first interval elapses', () => {
    const ticks: number[] = [];
    sub = visibleInterval(1000).subscribe((t) => ticks.push(t));
    vi.advanceTimersByTime(999);
    expect(ticks.length).toBe(0);
  });

  it('emits on each interval while the tab is visible', () => {
    const ticks: number[] = [];
    sub = visibleInterval(1000).subscribe((t) => ticks.push(t));
    vi.advanceTimersByTime(3000);
    expect(ticks.length).toBe(3);
  });

  it('pauses while hidden and resumes when visible again', () => {
    const ticks: number[] = [];
    sub = visibleInterval(1000).subscribe((t) => ticks.push(t));

    vi.advanceTimersByTime(2000);
    expect(ticks.length).toBe(2);

    setHidden(true);             // tab hidden → switch to EMPTY
    vi.advanceTimersByTime(5000);
    expect(ticks.length).toBe(2); // no ticks while hidden

    setHidden(false);            // visible again → cadence restarts
    vi.advanceTimersByTime(1000);
    expect(ticks.length).toBe(3);
  });
});
