import type { ChannelDelivery, NotificationChannel, NotificationRow } from '../api/notifications.service';
import { MockStore } from './mock-store';

/**
 * The C4 Notification-center mock domain's shared core: channel + delivery collections and the
 * {@link fanOut} helper other mock domains call when something notification-worthy happens (a fired
 * alert, an opened incident). Framework-free so ops/demo handlers and the simulator can all use it.
 */

export const NOTIFICATIONS_COLL = 'notification';
export const NOTIFICATION_CHANNELS_COLL = 'notification-channel';
export const NOTIFICATION_DELIVERIES_COLL = 'notification-delivery';

// Channel/delivery shapes are the canonical api ones (notifications.service.ts) — re-exported for handlers.
export type { ChannelDelivery, NotificationChannel };

let deliverySeq = 0;

/**
 * Fan a notification-worthy fact out: one in-app notification (feeds the bell + feed) plus one
 * delivery-ledger entry per ENABLED channel. Returns the created deliveries.
 */
export function fanOut(
    store: MockStore,
    space: string,
    trigger: string,
    category: string,
    title: string,
    body: string,
    sourceId: string | null = null,
): ChannelDelivery[] {
    const now = Date.now();
    const notif: NotificationRow = {
        id: `notif-${now}-${++deliverySeq}`,
        ts: now,
        timestamp: new Date(now).toISOString(),
        category,
        sourceType: trigger,
        sourceId,
        title,
        body,
        state: 'UNREAD',
        readAt: null,
    };
    store.put(space, NOTIFICATIONS_COLL, notif.id, notif);

    return store
        .list<NotificationChannel>(space, NOTIFICATION_CHANNELS_COLL)
        .filter((c) => c.enabled)
        .map((c) => {
            const d: ChannelDelivery = {
                id: `dlv-${now}-${++deliverySeq}`,
                ts: now,
                channelId: c.id,
                channelKind: c.kind,
                target: c.target,
                trigger,
                subject: title,
                status: 'SENT',
            };
            return store.put(space, NOTIFICATION_DELIVERIES_COLL, d.id, d);
        });
}
