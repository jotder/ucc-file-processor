package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Turns operational {@link Event}s into in-app {@link Notification}s. Registered as an
 * {@link com.gamma.event.EventLog} subscriber, so it sees every emitted event and applies the active
 * {@link NotificationRules}.
 *
 * <h3>Off-thread by design</h3>
 * {@code EventLog} notifies subscribers synchronously on the emitting thread — for a {@code BATCH_FAILED}
 * event that thread is inside the synchronous {@link com.gamma.service.BatchEventBus} publish while the
 * ingest path holds {@code ingestLock}. Doing real work inline would stall ingest (and risk the
 * documented bus/lock deadlock), so {@link #onEvent} only matches a rule and <b>hands off</b> to a
 * virtual-thread executor (the {@code JobService}/{@code triggerWorkers} idiom); rendering, dedup and
 * storage happen there.
 *
 * <p>Live {@link #addListener listeners} are invoked after a notification is stored — the seam the SSE
 * endpoint uses to push it to connected clients.
 *
 * @since 4.4.0
 */
public final class NotificationService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationStore store;
    private final NotificationRules rules;
    private final NotificationPreferences prefs;
    /** Caps identical notifications per rolling hour — the loop safeguard (beyond unread-dedup). */
    private final NotificationRateLimiter rateLimiter =
            new NotificationRateLimiter(NotificationRateLimiter.DEFAULT_MAX_PER_HOUR, NotificationRateLimiter.ONE_HOUR_MS);
    /** External SPI delivery channels (e.g. email), discovered via {@link ServiceLoader}; empty in the core. */
    private final List<NotificationChannel> channels;
    /** Live view of the operator's persisted {@link ChannelConfig} destinations (admin CRUD), resolved at
     *  dispatch time so channel edits take effect without a restart; {@code List::of} when none are wired. */
    private final Supplier<List<ChannelConfig>> channelConfigs;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final CopyOnWriteArrayList<Consumer<Notification>> listeners = new CopyOnWriteArrayList<>();
    /** Callbacks run on {@link #close()} to unblock open SSE streams (each interrupts its blocked thread). */
    private final CopyOnWriteArrayList<Runnable> streamClosers = new CopyOnWriteArrayList<>();

    /** Production constructor: external SPI channels are discovered via {@link ServiceLoader} (none in the
     *  core), with no persisted {@link ChannelConfig} destinations wired. */
    public NotificationService(NotificationStore store, NotificationRules rules, NotificationPreferences prefs) {
        this(store, rules, prefs, discoverChannels(), List::of);
    }

    /** Production constructor: discover SPI channels and wire a live view of the operator's persisted
     *  {@link ChannelConfig} destinations (admin CRUD), delivered through the matching SPI transport by kind. */
    public NotificationService(NotificationStore store, NotificationRules rules, NotificationPreferences prefs,
                               Supplier<List<ChannelConfig>> channelConfigs) {
        this(store, rules, prefs, discoverChannels(), channelConfigs);
    }

    /** Test/explicit constructor: inject the external channel list directly (no persisted destinations). */
    public NotificationService(NotificationStore store, NotificationRules rules, NotificationPreferences prefs,
                               List<NotificationChannel> channels) {
        this(store, rules, prefs, channels, List::of);
    }

    /** Full constructor: explicit SPI channels + a persisted-{@link ChannelConfig} supplier. */
    public NotificationService(NotificationStore store, NotificationRules rules, NotificationPreferences prefs,
                               List<NotificationChannel> channels, Supplier<List<ChannelConfig>> channelConfigs) {
        this.store = store;
        this.rules = rules;
        this.prefs = prefs;
        this.channels = List.copyOf(channels);
        this.channelConfigs = channelConfigs;
        if (!this.channels.isEmpty())
            log.info("Notification channels: {}", this.channels.stream().map(NotificationChannel::id).toList());
    }

    private static List<NotificationChannel> discoverChannels() {
        List<NotificationChannel> found = new ArrayList<>();
        for (NotificationChannel ch : ServiceLoader.load(NotificationChannel.class)) {
            if (ch.configured()) found.add(ch);
            else log.debug("notification channel {} registered but not configured — skipped", ch.id());
        }
        return found;
    }

    /** The feed store (for the Control API routes). */
    public NotificationStore store() {
        return store;
    }

    /** The {@link com.gamma.event.EventLog} subscriber. Cheap + non-blocking: filter, then hand off. */
    public void onEvent(Event e) {
        if (e == null || EventType.LOG.equals(e.type())) return;   // skip the high-volume capture stream
        rules.forEvent(e).ifPresent(rule -> {
            try {
                workers.execute(() -> dispatch(e, rule));
            } catch (RuntimeException ignore) {
                // executor shutting down — drop; notifications are best-effort, never block the emitter
            }
        });
    }

    /** Register a live listener invoked after each stored notification (e.g. the SSE pusher). */
    public void addListener(Consumer<Notification> listener) {
        if (listener != null) listeners.add(listener);
    }

    /** Remove a previously registered listener. */
    public void removeListener(Consumer<Notification> listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** Register a callback run on {@link #close()} to unblock an open SSE stream (e.g. interrupt its thread). */
    public void onClose(Runnable closer) {
        if (closer != null) streamClosers.add(closer);
    }

    /** Remove a previously registered close callback (when the stream ends normally). */
    public void removeOnClose(Runnable closer) {
        if (closer != null) streamClosers.remove(closer);
    }

    /** Render → dedup → store → notify, off the emitting thread. Serialized so the dedup check and the
     *  add are atomic across concurrent workers (the feed is low-volume; the executor exists to get off
     *  the emit thread, not for parallelism). */
    private synchronized void dispatch(Event e, NotificationRule rule) {
        try {
            Notification n = rule.render(e);
            if (store.hasActiveDuplicate(n.dedupeKey())) return;   // collapse identical unread alerts
            if (!rateLimiter.allow(n.dedupeKey())) return;          // cap identical alerts per rolling hour
            // In-app (intrinsic): add to the feed + push to live listeners, unless the user opted out of
            // in-app for this category (critical categories are always delivered — preferences bypass).
            if (prefs.enabled(n.category(), NotificationPreferences.IN_APP)) {
                Notification stored = store.add(n);
                for (Consumer<Notification> l : listeners) {
                    try { l.accept(stored); } catch (RuntimeException ex) {
                        log.debug("notification listener failed: {}", ex.getMessage());
                    }
                }
            }
            // External SPI channels configured from notify.* flags: delivered only when enabled for this category.
            for (NotificationChannel ch : channels) {
                if (!prefs.enabled(n.category(), ch.id())) continue;
                try { ch.deliver(n); } catch (Exception ex) {
                    log.warn("channel {} delivery failed: {}", ch.id(), ex.getMessage());
                }
            }
            // Persisted channel destinations (admin CRUD): deliver each enabled ChannelConfig through the SPI
            // transport whose id names its kind, to the config's own target — so an operator-managed
            // destination delivers without a restart. No transport for a kind ⇒ nothing to deliver through.
            for (ChannelConfig cfg : channelConfigs.get()) {
                if (!cfg.enabled()) continue;
                NotificationChannel ch = channelByKind(cfg.kind());
                if (ch == null || !prefs.enabled(n.category(), ch.id())) continue;
                try { ch.deliver(n, cfg.target()); } catch (Exception ex) {
                    log.warn("channel {} → {} delivery failed: {}", ch.id(), cfg.target(), ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("failed to dispatch notification for event {}: {}", e.eventId(), ex.getMessage());
        }
    }

    /** The discovered SPI transport whose {@link NotificationChannel#id() id} names this persisted channel's
     *  {@code kind} (case-insensitive), or {@code null} when no such transport is on the classpath. */
    private NotificationChannel channelByKind(String kind) {
        if (kind == null) return null;
        for (NotificationChannel ch : channels)
            if (ch.id().equalsIgnoreCase(kind)) return ch;
        return null;
    }

    @Override
    public void close() {
        for (Runnable r : streamClosers) {   // unblock any open SSE streams first
            try { r.run(); } catch (RuntimeException ignore) { /* best effort */ }
        }
        workers.close();   // drain in-flight dispatches
    }
}
