import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ConnectivityService } from 'app/inspecto/api/connectivity.service';

/**
 * Persistent connectivity banner. Shows at the top of the shell whenever the browser is offline or
 * the backend is unreachable (driven by {@link ConnectivityService}); hidden otherwise. Carries a
 * Retry button (pings /health) for the backend-down case.
 *
 * Accessible: `role="alert"` + `aria-live="assertive"` so the degraded state is announced; colors use
 * the shared amber/red Tailwind tones (no hardcoded values — passes the design-system guard).
 */
@Component({
    selector: 'inspecto-connectivity-banner',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
    template: `
        @if (conn.degraded()) {
            <div
                role="alert"
                aria-live="assertive"
                class="flex items-center gap-3 px-4 py-2 text-sm font-medium"
                [class]="
                    conn.reason() === 'offline'
                        ? 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-100'
                        : 'bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100'
                "
            >
                <mat-icon
                    class="icon-size-5 shrink-0"
                    [svgIcon]="
                        conn.reason() === 'offline'
                            ? 'heroicons_outline:signal-slash'
                            : 'heroicons_outline:exclamation-triangle'
                    "
                ></mat-icon>
                <span class="flex-auto">
                    @if (conn.reason() === 'offline') {
                        You're offline — changes can't be saved until your connection returns.
                    } @else {
                        Can't reach the backend. It may be restarting or unavailable; data shown may be stale.
                    }
                </span>
                @if (conn.reason() === 'backend') {
                    <button
                        mat-stroked-button
                        class="shrink-0"
                        [disabled]="conn.retrying()"
                        (click)="conn.retry()"
                    >
                        @if (conn.retrying()) {
                            <mat-progress-spinner diameter="16" mode="indeterminate" />
                            <span class="ml-2">Retrying…</span>
                        } @else {
                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-path"></mat-icon>
                            <span class="ml-1">Retry</span>
                        }
                    </button>
                }
            </div>
        }
    `,
})
export class ConnectivityBannerComponent {
    readonly conn = inject(ConnectivityService);
}
