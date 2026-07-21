package com.gamma.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.notify.Notification;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WebhookChannel} against a real in-process HTTP server: JSON payload shape, bearer-token header,
 * non-2xx as failure, and the {@code configured()} gate that keeps an unconfigured channel inert.
 */
class WebhookChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile int respondWith = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(respondWith, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    private static Notification sample() {
        return Notification.create("ops", "ALERT_FIRED", "evt-1",
                "Alert: reject-rate-high", "reject_rate 0.4 breached threshold 0.1", "alert:orders:reject-rate-high");
    }

    @Test
    void postsNotificationJsonWithBearerToken() throws Exception {
        WebhookChannel ch = new WebhookChannel(url(), "s3cret", 5);
        assertTrue(ch.configured());
        ch.deliver(sample());

        assertEquals(1, bodies.size());
        JsonNode body = JSON.readTree(bodies.get(0));
        assertEquals("Alert: reject-rate-high", body.get("title").asText());
        assertEquals("ops", body.get("category").asText());
        assertEquals("ALERT_FIRED", body.get("sourceType").asText());
        assertEquals("Bearer s3cret", authHeaders.get(0));
    }

    @Test
    void omitsAuthorizationWhenNoToken() throws Exception {
        new WebhookChannel(url(), null, 5).deliver(sample());
        assertNull(authHeaders.get(0));
    }

    @Test
    void non2xxResponseIsADeliveryFailure() {
        respondWith = 500;
        WebhookChannel ch = new WebhookChannel(url(), null, 5);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> ch.deliver(sample()));
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void unconfiguredWithoutUrl() {
        assertFalse(new WebhookChannel(null, null, 5).configured());
        assertFalse(new WebhookChannel("  ", null, 5).configured());
        assertEquals("webhook", new WebhookChannel(null, null, 5).id());
    }
}
