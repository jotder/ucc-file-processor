import { describe, expect, it } from 'vitest';
import type { NotificationChannel, NotificationRow } from '../api/notifications.service';
import { MockStore } from './mock-store';
import {
    fanOut,
    NOTIFICATION_CHANNELS_COLL,
    NOTIFICATION_DELIVERIES_COLL,
    NOTIFICATIONS_COLL,
} from './notify';

function channel(id: string, enabled: boolean): NotificationChannel {
    return { id, kind: 'EMAIL', target: id + '@example.com', enabled, createdAt: 1 };
}

describe('notify.fanOut', () => {
    it('always records the in-app notification, even with no channels', () => {
        const store = new MockStore();
        const deliveries = fanOut(store, 'default', 'ALERT_FIRED', 'OPS', 'Alert: x', 'boom');
        expect(deliveries).toEqual([]);
        const notifs = store.list<NotificationRow>('default', NOTIFICATIONS_COLL);
        expect(notifs.length).toBe(1);
        expect(notifs[0]).toMatchObject({ title: 'Alert: x', state: 'UNREAD', sourceType: 'ALERT_FIRED' });
    });

    it('delivers once per ENABLED channel only', () => {
        const store = new MockStore();
        store.put('default', NOTIFICATION_CHANNELS_COLL, 'on', channel('on', true));
        store.put('default', NOTIFICATION_CHANNELS_COLL, 'off', channel('off', false));

        const deliveries = fanOut(store, 'default', 'INCIDENT_OPENED', 'OPS', 'Incident: y', 'desc', 'obj-1');
        expect(deliveries.length).toBe(1);
        expect(deliveries[0]).toMatchObject({
            channelId: 'on',
            trigger: 'INCIDENT_OPENED',
            subject: 'Incident: y',
            status: 'SENT',
        });
        expect(store.list('default', NOTIFICATION_DELIVERIES_COLL).length).toBe(1);
    });
});
