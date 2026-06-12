import { EMPTY, fromEvent, merge, Observable, of, timer } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';

/** Emits the current page-visibility, now and on every visibilitychange. */
function whenVisible(): Observable<boolean> {
  return merge(of(null), fromEvent(document, 'visibilitychange')).pipe(
    map(() => !document.hidden),
  );
}

/**
 * A polling clock that pauses when the tab is hidden. Emits the first tick after `intervalMs`
 * (so callers do their own initial load in ngOnInit), then every `intervalMs`; when the page is
 * hidden it stops emitting, and restarts the cadence when the page becomes visible again. Pipe
 * through `takeUntilDestroyed` in the component to clean up.
 */
export function visibleInterval(intervalMs: number): Observable<number> {
  return whenVisible().pipe(
    switchMap((visible) => (visible ? timer(intervalMs, intervalMs) : EMPTY)),
  );
}

/** Default operator-console refresh cadence (ms). */
export const DEFAULT_REFRESH_MS = 15000;
