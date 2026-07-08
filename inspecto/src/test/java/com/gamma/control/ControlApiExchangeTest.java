package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Exchange routes over real HTTP: two Spaces (finance owner, audit consumer), an offered Dataset,
 * the offer→request→approve→revoke grant lifecycle, and fail-closed resolution — driven against a
 * multi-space ControlApi built from an empty {@code -Dspaces.root} container.
 */
class ControlApiExchangeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void offerRequestApproveRevokeOverHttp(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // two ministries
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"finance\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"audit\"}").statusCode());
            // finance authors a dataset component (so the offer has something real to point at)
            assertEquals(200, send(c.port, "POST", "/spaces/finance/components/dataset",
                    "{\"id\":\"tax_receipts\",\"physicalRef\":\"tax_receipts\"}").statusCode());

            // ── offering a non-existent dataset → 404 ──
            assertEquals(404, send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"ghost\",\"owner\":\"finance\"}").statusCode());

            // ── finance offers tax_receipts ──
            HttpResponse<String> offered = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\",\"description\":\"FY26\"}");
            assertEquals(200, offered.statusCode(), offered.body());
            JsonNode offers = json(send(c.port, "GET", "/exchange/offers?owner=finance", null));
            assertEquals(1, offers.size());
            assertEquals("tax_receipts", offers.get(0).get("item").asText());

            // ── audit requests use ──
            // self-request → 400; unoffered item → 404
            assertEquals(400, send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\",\"consumer\":\"finance\"}").statusCode());
            assertEquals(404, send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"dataset\",\"item\":\"nope\",\"owner\":\"finance\",\"consumer\":\"audit\"}").statusCode());

            HttpResponse<String> req = send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\",\"consumer\":\"audit\",\"purpose\":\"FY26 audit\"}");
            assertEquals(200, req.statusCode(), req.body());
            String id = json(req).get("id").asText();
            assertEquals("requested", json(req).get("status").asText());

            // audit's "shared with me" view shows the pending grant
            assertEquals(1, json(send(c.port, "GET", "/exchange/grants?space=audit", null)).size());

            // ── finance approves ──
            HttpResponse<String> approved = send(c.port, "POST", "/exchange/grants/" + id + "/approve", "");
            assertEquals(200, approved.statusCode(), approved.body());
            assertEquals("active", json(approved).get("status").asText());

            // dataset metadata carries the grant status for the consumer
            JsonNode meta = json(send(c.port, "GET", "/exchange/datasets/finance/tax_receipts?consumer=audit", null));
            assertEquals("active", meta.get("grant").get("status").asText());

            // ── revoke, then re-approving the revoked grant is a 409 ──
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/revoke", "").statusCode());
            assertEquals("revoked",
                    json(send(c.port, "GET", "/exchange/grants?space=finance", null)).get(0).get("status").asText());
            assertEquals(409, send(c.port, "POST", "/exchange/grants/" + id + "/approve", "").statusCode());

            // approving an unknown grant → 404
            assertEquals(404, send(c.port, "POST", "/exchange/grants/ghost/approve", "").statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
