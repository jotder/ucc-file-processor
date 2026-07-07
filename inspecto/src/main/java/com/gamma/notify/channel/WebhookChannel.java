package com.gamma.notify.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationChannel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Webhook delivery channel — POSTs each notification as JSON ({@link Notification#toMap()} shape, the
 * same document the {@code /notifications} API serves) to a configured URL. Pure-JDK
 * ({@link HttpClient}), so it lives in the lean core; it is inert until configured.
 *
 * <p>Configuration (system properties, the engine's config idiom for operational backends):
 * <ul>
 *   <li>{@code notify.webhook.url} — the target URL; unset ⇒ the channel is {@linkplain #configured()
 *       not configured} and never invoked.</li>
 *   <li>{@code notify.webhook.token} — optional bearer token sent as {@code Authorization: Bearer …}.</li>
 *   <li>{@code notify.webhook.timeout.seconds} — per-request timeout (default 10).</li>
 * </ul>
 *
 * <p>A non-2xx response is a delivery failure (thrown, logged and isolated per notification by
 * {@link com.gamma.notify.NotificationService}); there is no retry — notifications are best-effort and
 * the in-app feed remains the durable record.
 *
 * @since 4.5.0
 */
public final class WebhookChannel implements NotificationChannel {

    /** Preference-grid channel id. */
    public static final String ID = "webhook";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url;
    private final String token;
    private final Duration timeout;
    private final HttpClient client;

    /** ServiceLoader constructor: reads {@code notify.webhook.*} system properties. */
    public WebhookChannel() {
        this(System.getProperty("notify.webhook.url"),
             System.getProperty("notify.webhook.token"),
             Long.getLong("notify.webhook.timeout.seconds", 10L));
    }

    WebhookChannel(String url, String token, long timeoutSeconds) {
        this.url = url == null || url.isBlank() ? null : url.trim();
        this.token = token == null || token.isBlank() ? null : token.trim();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override public String id() { return ID; }

    @Override public boolean configured() { return url != null; }

    @Override
    public void deliver(Notification n) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(n.toMap())));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2)
            throw new IllegalStateException("webhook returned HTTP " + resp.statusCode());
    }
}
