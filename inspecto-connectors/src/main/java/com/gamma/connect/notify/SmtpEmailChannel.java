package com.gamma.connect.notify;

import com.gamma.notify.Notification;
import com.gamma.notify.NotificationChannel;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.Properties;

/**
 * SMTP email delivery channel — sends each notification as a plain-text mail. Lives in the connectors
 * module (network dependencies stay out of the lean core, the PKG-2 SBOM rule) and is discovered via
 * {@link java.util.ServiceLoader} when the connectors jar is on the classpath; it is inert until
 * configured.
 *
 * <p>Configuration (system properties, the engine's config idiom for operational backends):
 * <ul>
 *   <li>{@code notify.smtp.host} + {@code notify.smtp.to} — both required; either unset ⇒ the channel is
 *       {@linkplain #configured() not configured} and never invoked.</li>
 *   <li>{@code notify.smtp.port} — default {@code 25}.</li>
 *   <li>{@code notify.smtp.from} — default {@code inspecto@<host>}.</li>
 *   <li>{@code notify.smtp.user} / {@code notify.smtp.pass} — optional; both set ⇒ SMTP AUTH.</li>
 *   <li>{@code notify.smtp.starttls} — {@code true} to negotiate STARTTLS (default {@code false}).</li>
 * </ul>
 *
 * <p>{@link #deliver(Notification, String)} sends to an explicit {@code target} address (a persisted
 * {@link com.gamma.notify.ChannelConfig} destination), falling back to {@code notify.smtp.to} when
 * blank; {@link #deliver(Notification)} always uses the fixed {@code notify.smtp.to}.
 *
 * <p>Failures throw and are logged + isolated per notification by
 * {@link com.gamma.notify.NotificationService}; there is no retry — notifications are best-effort and
 * the in-app feed remains the durable record.
 *
 * @since 4.5.0
 */
public final class SmtpEmailChannel implements NotificationChannel {

    /** Preference-grid channel id — matches {@code NotificationPreferences.EMAIL}. */
    public static final String ID = "email";

    private final String host;
    private final int port;
    private final String from;
    private final String to;
    private final String user;
    private final String pass;
    private final boolean starttls;

    /** ServiceLoader constructor: reads {@code notify.smtp.*} system properties. */
    public SmtpEmailChannel() {
        this(System.getProperty("notify.smtp.host"),
             Integer.getInteger("notify.smtp.port", 25),
             System.getProperty("notify.smtp.from"),
             System.getProperty("notify.smtp.to"),
             System.getProperty("notify.smtp.user"),
             System.getProperty("notify.smtp.pass"),
             Boolean.getBoolean("notify.smtp.starttls"));
    }

    SmtpEmailChannel(String host, int port, String from, String to, String user, String pass, boolean starttls) {
        this.host = blankToNull(host);
        this.port = port;
        this.from = blankToNull(from) != null ? from.trim() : "inspecto@" + (this.host == null ? "localhost" : this.host);
        this.to = blankToNull(to);
        this.user = blankToNull(user);
        this.pass = blankToNull(pass);
        this.starttls = starttls;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    @Override public String id() { return ID; }

    @Override public boolean configured() { return host != null && to != null; }

    @Override
    public void deliver(Notification n) throws Exception {
        Transport.send(message(n));
    }

    /**
     * Deliver to an explicit {@code target} address (or comma-separated list) — the seam a persisted
     * {@link com.gamma.notify.ChannelConfig} destination is delivered through, so one SMTP transport
     * serves several operator-managed recipients instead of the single fixed {@code notify.smtp.to}.
     */
    @Override
    public void deliver(Notification n, String target) throws Exception {
        Transport.send(message(n, target));
    }

    /** Builds the outgoing mail — separated so tests can assert the message without a live SMTP server. */
    MimeMessage message(Notification n) throws Exception {
        return message(n, to);
    }

    /** As {@link #message(Notification)}, but addressed to an explicit {@code target} (blank ⇒ fixed {@code to}). */
    MimeMessage message(Notification n, String target) throws Exception {
        return buildMessage(n, target != null && !target.isBlank() ? target : to);
    }

    private MimeMessage buildMessage(Notification n, String recipients) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        if (starttls) props.put("mail.smtp.starttls.enable", "true");
        Session session;
        if (user != null && pass != null) {
            props.put("mail.smtp.auth", "true");
            session = Session.getInstance(props, new javax.mail.Authenticator() {
                @Override protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new javax.mail.PasswordAuthentication(user, pass);
                }
            });
        } else {
            session = Session.getInstance(props);
        }
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipients));
        msg.setSubject("[Inspecto] " + n.title());
        msg.setText(n.body() + "\n\ncategory: " + n.category()
                + "\nsource: " + n.sourceType() + (n.sourceId() == null ? "" : " (" + n.sourceId() + ")")
                + "\nat: " + n.timestamp());
        msg.setSentDate(new Date(n.ts()));
        return msg;
    }
}
