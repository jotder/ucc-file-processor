import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** One in-app notification (GET /notifications). Mirrors `com.gamma.notify.Notification#toMap()`. */
export interface NotificationRow {
    id: string;
    ts: number;
    timestamp: string;
    category: string;
    sourceType: string;
    sourceId: string | null;
    title: string;
    body: string;
    state: 'UNREAD' | 'READ' | 'ARCHIVED';
    readAt: number | null;
}

/** Per-channel toggles for one preference-grid category (in-app / email / future SPI channels). */
export interface ChannelToggles {
    inApp: boolean;
    email: boolean;
    [channel: string]: boolean;
}

/** One row of the preference grid (GET/PUT /notifications/preferences). */
export interface NotificationPrefRow {
    category: string;
    label: string;
    critical: boolean;
    available: boolean;
    channels: ChannelToggles;
}

/** A configured delivery channel (GET/POST /notifications/channels) — C4. Delivery is mocked (no real IO). */
export interface NotificationChannel {
    id: string;
    kind: 'EMAIL' | 'WEBHOOK' | string;
    /** Where deliveries go: an address for EMAIL, a URL for WEBHOOK. */
    target: string;
    description?: string;
    enabled: boolean;
    createdAt: number;
}

/** An authored notification rule (GET/POST /notifications/rules) — the event→notification mapping an
 *  operator can add/override at runtime; checked ahead of the backend's built-in defaults. */
export interface NotificationRule {
    id: string;
    /** The event type this rule fires on (e.g. BATCH_FAILED, job.custom) — case-insensitive. */
    eventType: string;
    /** Minimum severity, or null/absent for any. */
    minLevel?: 'INFO' | 'WARN' | 'ERROR' | string | null;
    /** Notification category (also the preference key gating delivery). */
    category: string;
    titleTemplate?: string;
    bodyTemplate?: string;
    dedupeKeyTemplate?: string;
    enabled: boolean;
}

/** One delivery-ledger entry: a notification handed to one channel (GET /notifications/deliveries) — C4. */
export interface ChannelDelivery {
    id: string;
    ts: number;
    channelId: string;
    channelKind: string;
    target: string;
    /** What caused it — e.g. ALERT_FIRED, INCIDENT_OPENED. */
    trigger: string;
    subject: string;
    status: 'SENT' | string;
}

/**
 * In-app notification feed (the bell) + preference grid. Holds the feed and unread badge as signals so
 * the toolbar bell renders reactively; the real-time SSE stream and a polling fallback both flow through
 * {@link applyIncoming} / {@link refresh} here.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
    private http = inject(HttpClient);

    /** Active (non-archived) feed, newest-first. */
    readonly items = signal<NotificationRow[]>([]);
    /** Unread badge count. */
    readonly unreadCount = signal<number>(0);
    /** True while the initial feed load is in flight. */
    readonly loading = signal<boolean>(false);

    /** Reload the feed + unread badge (initial load and the polling fallback). */
    refresh(): void {
        this.loading.set(true);
        this.http
            .get<NotificationRow[]>(apiUrl('/notifications'), { params: toParams({ limit: 50 }) })
            .subscribe({
                next: (rows) => {
                    this.items.set(rows);
                    this.loading.set(false);
                },
                error: () => this.loading.set(false),
            });
        this.http
            .get<{ count: number }>(apiUrl('/notifications/unread-count'))
            .subscribe({ next: (r) => this.unreadCount.set(r.count) });
    }

    /** Push a notification arriving over SSE to the front of the feed (no reload). */
    applyIncoming(n: NotificationRow): void {
        if (this.items().some((x) => x.id === n.id)) return;
        this.items.update((rows) => [n, ...rows]);
        if (n.state === 'UNREAD') this.unreadCount.update((c) => c + 1);
    }

    /** Mark one notification read (POST /notifications/{id}/read), updating local state on success. */
    markRead(id: string): void {
        this.http.post(apiUrl(`/notifications/${encodeURIComponent(id)}/read`), {}).subscribe({
            next: () => {
                this.items.update((rows) =>
                    rows.map((n) => (n.id === id ? { ...n, state: 'READ' as const } : n)),
                );
                this.unreadCount.update((c) => Math.max(0, c - 1));
            },
        });
    }

    /** Mark every notification read (POST /notifications/read-all). */
    markAllRead(): void {
        this.http.post(apiUrl('/notifications/read-all'), {}).subscribe({
            next: () => {
                this.items.update((rows) => rows.map((n) => ({ ...n, state: 'READ' as const })));
                this.unreadCount.set(0);
            },
        });
    }

    /** Delete (archive) one notification (DELETE /notifications/{id}), removing it from the feed. */
    remove(id: string): void {
        this.http.delete(apiUrl(`/notifications/${encodeURIComponent(id)}`)).subscribe({
            next: () => {
                const was = this.items().find((n) => n.id === id);
                this.items.update((rows) => rows.filter((n) => n.id !== id));
                if (was?.state === 'UNREAD') this.unreadCount.update((c) => Math.max(0, c - 1));
            },
        });
    }

    /** The preference grid (GET /notifications/preferences). */
    preferences(): Observable<NotificationPrefRow[]> {
        return this.http.get<NotificationPrefRow[]>(apiUrl('/notifications/preferences'));
    }

    /** Persist the edited preference grid (PUT /notifications/preferences); returns the refreshed grid. */
    savePreferences(rows: NotificationPrefRow[]): Observable<NotificationPrefRow[]> {
        return this.http.put<NotificationPrefRow[]>(apiUrl('/notifications/preferences'), {
            preferences: rows,
        });
    }

    // ── C4 Notification center: channels + delivery ledger ─────────────────────

    /** Configured delivery channels (GET /notifications/channels). */
    channels(): Observable<NotificationChannel[]> {
        return this.http.get<NotificationChannel[]>(apiUrl('/notifications/channels'));
    }

    /** Create a channel (POST /notifications/channels); 409 on a duplicate id. */
    createChannel(ch: Omit<NotificationChannel, 'createdAt'>): Observable<NotificationChannel> {
        return this.http.post<NotificationChannel>(apiUrl('/notifications/channels'), ch);
    }

    /** Update a channel — config edit or enable/disable (PUT /notifications/channels/{id}). */
    updateChannel(id: string, patch: Partial<NotificationChannel>): Observable<NotificationChannel> {
        return this.http.put<NotificationChannel>(apiUrl(`/notifications/channels/${encodeURIComponent(id)}`), patch);
    }

    /** Delete a channel (DELETE /notifications/channels/{id}). */
    deleteChannel(id: string): Observable<unknown> {
        return this.http.delete(apiUrl(`/notifications/channels/${encodeURIComponent(id)}`));
    }

    // ── authored notification rules (mirror of the channels CRUD) ──────────────

    /** The operator-authored rules (GET /notifications/rules). */
    rules(): Observable<NotificationRule[]> {
        return this.http.get<NotificationRule[]>(apiUrl('/notifications/rules'));
    }

    /** Create a rule (POST /notifications/rules); 409 on a duplicate id, 422 on missing fields. */
    createRule(rule: NotificationRule): Observable<NotificationRule> {
        return this.http.post<NotificationRule>(apiUrl('/notifications/rules'), rule);
    }

    /** Update a rule — edit or enable/disable (PUT /notifications/rules/{id}). */
    updateRule(id: string, patch: Partial<NotificationRule>): Observable<NotificationRule> {
        return this.http.put<NotificationRule>(apiUrl(`/notifications/rules/${encodeURIComponent(id)}`), patch);
    }

    /** Delete a rule (DELETE /notifications/rules/{id}). */
    deleteRule(id: string): Observable<unknown> {
        return this.http.delete(apiUrl(`/notifications/rules/${encodeURIComponent(id)}`));
    }

    /** The delivery ledger, newest-first (GET /notifications/deliveries). */
    deliveries(limit = 200): Observable<ChannelDelivery[]> {
        return this.http.get<ChannelDelivery[]>(apiUrl('/notifications/deliveries'), {
            params: toParams({ limit }),
        });
    }
}
