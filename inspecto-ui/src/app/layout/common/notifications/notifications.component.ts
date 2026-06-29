import { DatePipe } from '@angular/common';
import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    OnDestroy,
    OnInit,
    computed,
    inject,
    signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ConnectedPosition, OverlayModule } from '@angular/cdk/overlay';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { apiUrl, DEFAULT_REFRESH_MS, NotificationsService, visibleInterval } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

/**
 * Toolbar "bell" — the in-app notification feed. Shows an unread badge and, in a popover, the recent
 * feed with mark-read / mark-all / delete actions. Live updates arrive over SSE
 * ({@code GET /notifications/stream}); if the stream is unavailable (or in jsdom, which has no
 * {@code EventSource}) it falls back to visibility-aware polling. Container component: it owns the
 * connection and delegates all HTTP + state to {@link NotificationsService}.
 */
@Component({
    selector: 'inspecto-notification-bell',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        DatePipe,
        OverlayModule,
        MatBadgeModule,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        StatusBadgeComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
    ],
    template: `
        <button
            mat-icon-button
            type="button"
            cdkOverlayOrigin
            #trigger="cdkOverlayOrigin"
            (click)="toggle()"
            [attr.aria-label]="ariaLabel()"
            [attr.aria-expanded]="open()"
            matTooltip="Notifications"
        >
            <mat-icon
                svgIcon="heroicons_outline:bell"
                [matBadge]="badge()"
                matBadgeSize="small"
                matBadgeColor="warn"
                [matBadgeHidden]="svc.unreadCount() === 0"
            ></mat-icon>
        </button>

        <ng-template
            cdkConnectedOverlay
            [cdkConnectedOverlayOrigin]="trigger"
            [cdkConnectedOverlayOpen]="open()"
            [cdkConnectedOverlayPositions]="positions"
            [cdkConnectedOverlayHasBackdrop]="true"
            cdkConnectedOverlayBackdropClass="cdk-overlay-transparent-backdrop"
            (backdropClick)="close()"
            (detach)="close()"
        >
            <div
                role="dialog"
                aria-label="Notifications"
                class="bg-card flex max-h-[28rem] w-[22rem] flex-col overflow-hidden rounded-lg border shadow-lg"
            >
                <div class="flex items-center justify-between border-b px-4 py-3">
                    <h2 class="text-base font-semibold">Notifications</h2>
                    <button
                        mat-button
                        type="button"
                        (click)="svc.markAllRead()"
                        [disabled]="svc.unreadCount() === 0"
                    >
                        Mark all read
                    </button>
                </div>

                <div class="flex-auto overflow-y-auto">
                    @if (svc.loading() && svc.items().length === 0) {
                        <div class="p-4"><inspecto-skeleton [lines]="4" /></div>
                    } @else if (svc.items().length === 0) {
                        <inspecto-empty-state
                            class="block p-4"
                            icon="heroicons_outline:bell-slash"
                            message="You're all caught up."
                        />
                    } @else {
                        <ul class="divide-y">
                            @for (n of svc.items(); track n.id) {
                                <li class="flex gap-3 px-4 py-3" [class.opacity-60]="n.state === 'READ'">
                                    <span
                                        class="mt-1.5 h-2 w-2 shrink-0 rounded-full"
                                        [class.bg-primary]="n.state === 'UNREAD'"
                                        aria-hidden="true"
                                    ></span>
                                    <div class="min-w-0 flex-auto">
                                        <span
                                            class="block truncate text-sm"
                                            [class.font-semibold]="n.state === 'UNREAD'"
                                            >{{ n.title }}</span
                                        >
                                        @if (n.body) {
                                            <div class="text-secondary mt-0.5 truncate text-sm">{{ n.body }}</div>
                                        }
                                        <div class="mt-1 flex items-center gap-2">
                                            <inspecto-status-badge [value]="n.category" />
                                            <span class="text-secondary text-xs">{{ n.ts | date: 'short' }}</span>
                                        </div>
                                    </div>
                                    <div class="flex shrink-0 flex-col gap-1">
                                        @if (n.state === 'UNREAD') {
                                            <button
                                                mat-icon-button
                                                type="button"
                                                matTooltip="Mark as read"
                                                aria-label="Mark as read"
                                                (click)="svc.markRead(n.id)"
                                            >
                                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:check"></mat-icon>
                                            </button>
                                        }
                                        <button
                                            mat-icon-button
                                            type="button"
                                            matTooltip="Delete"
                                            aria-label="Delete notification"
                                            (click)="svc.remove(n.id)"
                                        >
                                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:trash"></mat-icon>
                                        </button>
                                    </div>
                                </li>
                            }
                        </ul>
                    }
                </div>
            </div>
        </ng-template>
    `,
})
export class NotificationBellComponent implements OnInit, OnDestroy {
    readonly svc = inject(NotificationsService);
    private destroyRef = inject(DestroyRef);

    readonly open = signal(false);
    private source?: EventSource;
    private polling?: Subscription;

    /** Anchor the panel's right edge under the bell so it never overflows the right viewport edge. */
    readonly positions: ConnectedPosition[] = [
        { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top', offsetY: 8 },
    ];

    readonly badge = computed(() => {
        const c = this.svc.unreadCount();
        return c > 99 ? '99+' : String(c);
    });

    readonly ariaLabel = computed(() => {
        const c = this.svc.unreadCount();
        return c > 0 ? `Notifications, ${c} unread` : 'Notifications';
    });

    ngOnInit(): void {
        this.svc.refresh();
        this.connect();
    }

    ngOnDestroy(): void {
        this.source?.close();
    }

    toggle(): void {
        this.open.update((o) => !o);
        if (this.open()) this.svc.refresh();
    }

    close(): void {
        this.open.set(false);
    }

    /** Open the SSE stream; fall back to polling if EventSource is unavailable or the stream errors. */
    private connect(): void {
        if (typeof EventSource === 'undefined') {
            this.startPolling();
            return;
        }
        try {
            const source = new EventSource(apiUrl('/notifications/stream'));
            source.onmessage = (e) => {
                try {
                    this.svc.applyIncoming(JSON.parse(e.data));
                } catch {
                    /* ignore malformed frame */
                }
            };
            source.onerror = () => {
                source.close();
                this.startPolling();
            };
            this.source = source;
        } catch {
            this.startPolling();
        }
    }

    private startPolling(): void {
        if (this.polling) return;
        this.polling = visibleInterval(DEFAULT_REFRESH_MS)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.svc.refresh());
    }
}
