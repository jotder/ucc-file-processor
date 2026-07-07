package com.gamma.notify;

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
public interface NotificationChannel {

    /** Stable channel id used as the preference key (e.g. {@code "email"}). */
    String id();

    /** Deliver one notification over this channel. May throw; the caller isolates failures. */
    void deliver(Notification n) throws Exception;

    /**
     * Whether this channel has the configuration it needs to deliver (e.g. an SMTP host or webhook URL).
     * {@link NotificationService} skips unconfigured channels at discovery, so a registered-but-unconfigured
     * channel is inert rather than a per-notification failure. Defaults to {@code true} for channels that
     * need no configuration.
     */
    default boolean configured() { return true; }
}
