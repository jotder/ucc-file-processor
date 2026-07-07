package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the event→feed engine. Dispatch is asynchronous, so each test fires events and then
 * {@link NotificationService#close()}s the service to drain its executor before asserting.
 */
class NotificationServiceTest {

    private static Event batchFailed(String pipeline, String batchId, String msg) {
        return Event.builder(EventType.BATCH_FAILED).pipeline(pipeline).correlationId(batchId)
                .message(msg).build();
    }

    @Test
    void rendersOperationalEventIntoNotification() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences());

        svc.onEvent(batchFailed("orders", "b1", "boom"));
        svc.onEvent(Event.builder(EventType.LOG).message("just a log line").build());   // ignored
        svc.close();   // drains the virtual-thread executor

        List<Notification> feed = store.recent(10);
        assertEquals(1, feed.size(), "only the operational event produced a notification");
        Notification n = feed.get(0);
        assertEquals("Pipeline orders failed", n.title());
        assertEquals("Batch b1 failed: boom", n.body());
        assertEquals("pipeline", n.category());
        assertEquals(NotificationState.UNREAD, n.state());
    }

    @Test
    void collapsesDuplicateAlertsWhileUnread() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences());

        svc.onEvent(batchFailed("orders", "b1", "boom"));
        svc.onEvent(batchFailed("orders", "b2", "boom again"));   // same dedupeKey batch-failed:orders
        svc.close();

        assertEquals(1, store.recent(10).size(), "identical unread alerts collapse to one");
    }

    @Test
    void unknownEventTypeProducesNothing() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences());

        svc.onEvent(Event.builder("SOMETHING_UNMAPPED").message("x").build());
        svc.close();

        assertTrue(store.recent(10).isEmpty());
    }

    @Test
    void inAppOptOutSuppressesTheFeed() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.set("pipeline", Map.of(NotificationPreferences.IN_APP, false, NotificationPreferences.EMAIL, false));
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(), prefs, List.of());

        svc.onEvent(batchFailed("orders", "b1", "boom"));
        svc.close();

        assertTrue(store.recent(10).isEmpty(), "in-app opt-out keeps it out of the feed");
    }

    @Test
    void alertFiredEventProducesOpsNotification() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences());

        svc.onEvent(Event.builder(EventType.ALERT_FIRED).pipeline("orders")
                .message("reject_rate 0.4 breached threshold 0.1")
                .attr("rule", "reject-rate-high").attr("severity", "critical").build());
        svc.close();

        List<Notification> feed = store.recent(10);
        assertEquals(1, feed.size(), "a fired Alert Rule reaches the Notification Center");
        assertEquals("Alert: reject-rate-high", feed.get(0).title());
        assertEquals("ops", feed.get(0).category());
    }

    @Test
    void externalChannelReceivesEnabledDelivery() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.set("pipeline", Map.of(NotificationPreferences.IN_APP, true, NotificationPreferences.EMAIL, true));
        List<Notification> delivered = new CopyOnWriteArrayList<>();
        NotificationChannel email = new NotificationChannel() {
            public String id() { return NotificationPreferences.EMAIL; }
            public void deliver(Notification n) { delivered.add(n); }
        };
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(), prefs, List.of(email));

        svc.onEvent(batchFailed("orders", "b1", "boom"));
        svc.close();

        assertEquals(1, store.recent(10).size(), "in-app still delivered");
        assertEquals(1, delivered.size(), "email channel delivered (enabled for pipeline)");
        assertEquals("Pipeline orders failed", delivered.get(0).title());
    }
}
