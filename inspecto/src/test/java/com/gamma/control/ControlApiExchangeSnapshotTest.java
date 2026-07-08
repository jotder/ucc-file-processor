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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2 over real HTTP: refreshing an offered Dataset publishes a snapshot (freshness surfaces in the
 * catalog), and the Exchange deletion fence blocks deleting an item still shared with a consumer.
 */
class ControlApiExchangeSnapshotTest {

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
    void refreshPublishesSnapshotAndFenceBlocksDelete(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"finance\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"audit\"}").statusCode());

            // seed a real Parquet Table in finance's data root + a dataset over it
            Path table = root.resolve("finance").resolve("data").resolve("tax_receipts");
            Files.createDirectories(table);
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:"); Statement s = conn.createStatement()) {
                s.execute("COPY (SELECT * FROM (VALUES (1,'a'),(2,'b'),(3,'c')) t(amount,label)) TO "
                        + "'" + table.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            assertEquals(200, send(c.port, "POST", "/spaces/finance/components/dataset",
                    "{\"id\":\"tax_receipts\",\"physicalRef\":\"tax_receipts\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\"}").statusCode());

            // ── refresh publishes a snapshot ──
            HttpResponse<String> refreshed = send(c.port, "POST", "/exchange/refresh",
                    "{\"owner\":\"finance\",\"item\":\"tax_receipts\"}");
            assertEquals(200, refreshed.statusCode(), refreshed.body());
            assertEquals(3, json(refreshed).get("rows").asInt());
            assertTrue(Files.exists(root.resolve("_shared").resolve("exchange")
                    .resolve("finance").resolve("tax_receipts").resolve("current.toon")));

            // freshness now surfaces in the catalog
            JsonNode offer = json(send(c.port, "GET", "/exchange/offers?owner=finance", null)).get(0);
            assertEquals(3, offer.get("freshness").get("rows").asInt());

            // ── grant to audit, then the deletion fence blocks deleting the offered dataset ──
            String id = json(send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\",\"consumer\":\"audit\"}"))
                    .get("id").asText();
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/approve", "").statusCode());
            assertEquals(409, send(c.port, "DELETE", "/spaces/finance/components/dataset/tax_receipts", null).statusCode(),
                    "cannot delete a dataset still shared with a consumer");

            // revoke lifts the fence
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/revoke", "").statusCode());
            assertEquals(200, send(c.port, "DELETE", "/spaces/finance/components/dataset/tax_receipts", null).statusCode());
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
