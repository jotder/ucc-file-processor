package com.gamma.notify;

import com.gamma.api.PublicApi;

/**
 * SPI for an <em>external</em> notification delivery channel — the seam editions plug Email (SendGrid /
 * Amazon SES / Mailgun / SMTP) or webhooks into. Discovered via {@link java.util.ServiceLoader}, so the
 * lean Personal core ships <b>no</b> channel (in-app delivery is intrinsic to {@link NotificationService})
 * and a Standard/Enterprise build adds one by dropping its module on the classpath — the editions pattern,
 * never an {@code if (edition==…)} branch.
 *
 * <p>Delivery is gated per category by {@link NotificationPreferences} keyed on {@link #id()}, except for
 * {@link NotificationCategory#critical() critical} categories which always deliver. Implementations must
 * tolerate being called from a virtual-thread worker and must not block the engine.
 *
 * @since 4.4.0
 */
@PublicApi(since = "4.4.0")
public interface NotificationChannel {

    /** Stable channel id used as the preference key (e.g. {@code "email"}). */
    String id();

    /** Deliver one notification over this channel. May throw; the caller isolates failures. */
    void deliver(Notification n) throws Exception;

    /**
     * Deliver one notification to an explicit {@code target} destination (an address / URL) — the seam a
     * persisted {@link ChannelConfig} destination is delivered through, so one transport implementation can
     * serve several managed destinations of its {@link #id() kind}. Defaults to {@link #deliver(Notification)}
     * for impls that resolve their destination from configuration (e.g. an SMTP host from {@code notify.*}
     * flags) and don't yet honour a per-destination target.
     */
    default void deliver(Notification n, String target) throws Exception { deliver(n); }

    /**
     * Whether this channel has the configuration it needs to deliver (e.g. an SMTP host or webhook URL).
     * {@link NotificationService} skips unconfigured channels at discovery, so a registered-but-unconfigured
     * channel is inert rather than a per-notification failure. Defaults to {@code true} for channels that
     * need no configuration.
     */
    default boolean configured() { return true; }
}
