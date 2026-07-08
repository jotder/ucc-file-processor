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
 * S2b over real HTTP: a shared Widget renders read-only only when both its grant and its bound Dataset
 * grant are active; offering requires the dataset offer first; revoking the dataset grant cascades.
 */
class ControlApiExchangeWidgetTest {

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
    void widgetSharesRenderOnlyWithDatasetClosure(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"finance\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"audit\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces/finance/components/dataset",
                    "{\"id\":\"tax_receipts\",\"physicalRef\":\"tax_receipts\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces/finance/components/widget",
                    "{\"id\":\"receipts_chart\",\"type\":\"bar\",\"dataset\":\"tax_receipts\"}").statusCode());

            // offering the widget before its dataset → 409 (closure)
            assertEquals(409, send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"widget\",\"item\":\"receipts_chart\",\"owner\":\"finance\"}").statusCode());

            // offer the dataset, then the widget
            assertEquals(200, send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\"}").statusCode());
            HttpResponse<String> wo = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"widget\",\"item\":\"receipts_chart\",\"owner\":\"finance\"}");
            assertEquals(200, wo.statusCode(), wo.body());
            assertEquals("tax_receipts", json(wo).get("dataset").asText());

            // audit requests the widget → widget + dataset grants both created
            String wid = json(send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"widget\",\"item\":\"receipts_chart\",\"owner\":\"finance\",\"consumer\":\"audit\"}"))
                    .get("id").asText();
            assertEquals(2, json(send(c.port, "GET", "/exchange/grants?space=audit", null)).size());

            // not renderable while pending
            assertEquals(403, send(c.port, "GET", "/exchange/widgets/finance/receipts_chart?consumer=audit", null).statusCode());

            // approving the widget activates the pair; now it renders read-only
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + wid + "/approve", "").statusCode());
            HttpResponse<String> render = send(c.port, "GET", "/exchange/widgets/finance/receipts_chart?consumer=audit", null);
            assertEquals(200, render.statusCode(), render.body());
            assertEquals("shared/finance/tax_receipts", json(render).get("dataset").asText());
            assertTrue(json(render).get("readOnly").asBoolean());

            // revoking the dataset grant cascades → widget no longer renders
            String dgid = "audit~finance~dataset~tax_receipts";
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + dgid + "/revoke", "").statusCode());
            assertEquals(403, send(c.port, "GET", "/exchange/widgets/finance/receipts_chart?consumer=audit", null).statusCode());
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
