import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, finalize, of } from 'rxjs';
import { HealthService } from './health.service';

/**
 * Tracks whether the app can reach the world: the browser's network status and whether the
 * backend is responding. The {@link errorInterceptor} feeds it (HTTP `status === 0` ⇒ backend
 * down; any successful response ⇒ back up), and it listens to the browser online/offline events.
 *
 * A single source of truth so the UI shows one persistent, accessible banner
 * (`<inspecto-connectivity-banner>`) instead of every screen inventing its own "unreachable"
 * toast. Signal-based so components/computed read it reactively.
 */
@Injectable({ providedIn: 'root' })
export class ConnectivityService {
    private health = inject(HealthService);

    /** Browser network status. */
    readonly online = signal(typeof navigator === 'undefined' ? true : navigator.onLine);
    /** Whether the last backend interaction succeeded (false after a status-0 / network failure). */
    readonly backendReachable = signal(true);
    /** True while a manual retry probe is in flight. */
    readonly retrying = signal(false);

    /** Degraded = the browser is offline OR the backend is unreachable. Drives the banner. */
    readonly degraded = computed(() => !this.online() || !this.backendReachable());
    /** Which kind of problem, for the banner copy. */
    readonly reason = computed<'offline' | 'backend' | null>(() =>
        !this.online() ? 'offline' : !this.backendReachable() ? 'backend' : null,
    );

    constructor() {
        if (typeof window !== 'undefined') {
            window.addEventListener('online', () => this.online.set(true));
            window.addEventListener('offline', () => this.online.set(false));
        }
    }

    /** Called by the error interceptor when a request fails with no response (network/backend down). */
    reportUnreachable(): void {
        this.backendReachable.set(false);
    }

    /** Called by the error interceptor on any successful response — the backend is clearly up. */
    reportReachable(): void {
        this.backendReachable.set(true);
    }

    /**
     * Manual "Retry" from the banner: ping the public /health probe. Success clears the backend-down
     * state (a status-0 failure re-trips it via the interceptor). No-op visual beyond `retrying`.
     */
    retry(): void {
        if (this.retrying()) return;
        this.retrying.set(true);
        this.health
            .health()
            .pipe(
                catchError(() => of(null)),
                finalize(() => this.retrying.set(false)),
            )
            .subscribe((res) => {
                if (res) this.backendReachable.set(true);
            });
    }
}
