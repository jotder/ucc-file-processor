package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
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
    void conservationLossReachesTheFeedAsAnOpsNotification() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences());

        svc.onEvent(Event.builder(EventType.FLOW_CONSERVATION_IMBALANCE).level(EventLevel.ERROR)
                .pipeline("orders").correlationId("b1")
                .attr("node", "dedupe").attr("recordsIn", "1000").attr("recordsOut", "980")
                .attr("kind", "LOSS").build());
        svc.close();

        List<Notification> feed = store.recent(10);
        assertEquals(1, feed.size(), "a conservation LOSS reaches the Notification Center");
        assertEquals("Conservation LOSS in orders", feed.get(0).title());
        assertEquals("dedupe: 1000 in vs 980 out", feed.get(0).body());
        assertEquals("ops", feed.get(0).category());
    }

    @Test
    void conservationAmplificationAlsoNotifiesDespiteBeingWarn() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences());

        // AMPLIFICATION carries EventLevel.WARN — the rule's minLevel WARN must still match it.
        svc.onEvent(Event.builder(EventType.FLOW_CONSERVATION_IMBALANCE).level(EventLevel.WARN)
                .pipeline("orders").correlationId("b2")
                .attr("node", "fanout").attr("recordsIn", "1000").attr("recordsOut", "1200")
                .attr("kind", "AMPLIFICATION").build());
        svc.close();

        List<Notification> feed = store.recent(10);
        assertEquals(1, feed.size(), "an AMPLIFICATION (WARN) also notifies, matching the ALERT bridge");
        assertEquals("Conservation AMPLIFICATION in orders", feed.get(0).title());
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

    @Test
    void persistedChannelDeliveredThroughMatchingTransportToItsTarget() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.set("pipeline", Map.of(NotificationPreferences.IN_APP, true, NotificationPreferences.EMAIL, true));
        List<String> targets = new CopyOnWriteArrayList<>();
        NotificationChannel email = new NotificationChannel() {
            public String id() { return NotificationPreferences.EMAIL; }
            public void deliver(Notification n) { targets.add("flag"); }
            public void deliver(Notification n, String target) { targets.add(target); }
        };
        // A persisted EMAIL destination (kind matches the transport id, case-insensitively).
        ChannelConfig cfg = new ChannelConfig("c1", "EMAIL", "ops@x.com", null, true, 0L, null, 0);
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(), prefs,
                List.of(email), () -> List.of(cfg));

        svc.onEvent(batchFailed("orders", "b1", "boom"));
        svc.close();

        assertTrue(targets.contains("ops@x.com"),
                "the persisted EMAIL destination delivered through the matching transport, to its own target");
    }

    @Test
    void persistedChannelSkippedWhenDisabledOrNoTransportForItsKind() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.set("pipeline", Map.of(NotificationPreferences.IN_APP, true, NotificationPreferences.EMAIL, true));
        List<String> targets = new CopyOnWriteArrayList<>();
        NotificationChannel email = new NotificationChannel() {
            public String id() { return NotificationPreferences.EMAIL; }
            public void deliver(Notification n) { }
            public void deliver(Notification n, String target) { targets.add(target); }
        };
        ChannelConfig disabled = new ChannelConfig("c1", "EMAIL", "off@x.com", null, false, 0L, null, 0);
        ChannelConfig noTransport = new ChannelConfig("c2", "SLACK", "http://hook", null, true, 0L, null, 0);
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(), prefs,
                List.of(email), () -> List.of(disabled, noTransport));

        svc.onEvent(batchFailed("orders", "b1", "boom"));
        svc.close();

        assertTrue(targets.isEmpty(),
                "a disabled destination and one with no transport for its kind both deliver nothing");
    }

    @Test
    void persistedChannelWithItsOwnTemplateOverridesTheRuleDefaultBody() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.set("pipeline", Map.of(NotificationPreferences.IN_APP, true, NotificationPreferences.EMAIL, true));
        List<String> delivered = new CopyOnWriteArrayList<>();
        NotificationChannel email = new NotificationChannel() {
            public String id() { return NotificationPreferences.EMAIL; }
            public void deliver(Notification n) { }
            public void deliver(Notification n, String target) { delivered.add(n.body()); }
        };
        ChannelConfig cfg = new ChannelConfig("c1", "EMAIL", "ops@x.com", null, true, 0L,
                "[{{type}}] {{message}}", 0);
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(), prefs,
                List.of(email), () -> List.of(cfg));

        svc.onEvent(batchFailed("orders", "b1", "boom"));
        svc.close();

        assertEquals(1, delivered.size());
        assertEquals("[BATCH_FAILED] boom", delivered.get(0),
                "the channel's own template rendered instead of the rule's default body");
        assertEquals("Batch b1 failed: boom", store.recent(10).get(0).body(),
                "the stored/in-app copy is unaffected — only the channel delivery used the override");
    }

    @Test
    void digestWindowBuffersAndFlushesOneCombinedDelivery() {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.set("pipeline", Map.of(NotificationPreferences.IN_APP, true, NotificationPreferences.EMAIL, true));
        List<Notification> delivered = new CopyOnWriteArrayList<>();
        NotificationChannel email = new NotificationChannel() {
            public String id() { return NotificationPreferences.EMAIL; }
            public void deliver(Notification n) { }
            public void deliver(Notification n, String target) { delivered.add(n); }
        };
        // A 60-minute digest window — far longer than the test, so only close()'s flush delivers.
        ChannelConfig cfg = new ChannelConfig("c1", "EMAIL", "ops@x.com", null, true, 0L, null, 60);
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(), prefs,
                List.of(email), () -> List.of(cfg));

        svc.onEvent(batchFailed("orders", "b1", "boom"));
        svc.onEvent(batchFailed("billing", "b2", "bang"));
        svc.close();   // drains dispatch, then flushes the pending digest

        assertEquals(2, store.recent(10).size(), "in-app copies still delivered per-event");
        assertEquals(1, delivered.size(), "two buffered events flushed as ONE digest delivery");
        Notification digest = delivered.get(0);
        assertEquals("Digest: 2 notifications", digest.title());
        assertTrue(digest.body().contains("Pipeline orders failed"), digest.body());
        assertTrue(digest.body().contains("Pipeline billing failed"), digest.body());
    }

    @Test
    void flushDigestIsANoOpWhenNothingIsBuffered() {
        NotificationStore store = new InMemoryNotificationStore();
        List<Notification> delivered = new CopyOnWriteArrayList<>();
        NotificationChannel email = new NotificationChannel() {
            public String id() { return NotificationPreferences.EMAIL; }
            public void deliver(Notification n) { delivered.add(n); }
        };
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(),
                new NotificationPreferences(), List.of(email));
        svc.flushDigest("no-such-config");
        svc.close();
        assertTrue(delivered.isEmpty());
    }
}
