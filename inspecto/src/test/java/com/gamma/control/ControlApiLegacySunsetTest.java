package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-5 sunset mechanism: legacy (non-v1) responses carry the deprecation signalling headers
 * ({@code Deprecation}/{@code Link}, plus {@code Sunset} once {@code -Dapi.legacy.sunset} is signed);
 * {@code -Dapi.legacy.routes=off} retires the whole legacy surface with a 410 pointing at {@code /api/v1}
 * while v1 and the always-unversioned infra probes stay untouched and the usage metric keeps counting.
 */
class ControlApiLegacySunsetTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpResponse<String> get(ControlApi api, String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + path))
                .GET().build(), BodyHandlers.ofString());
    }

    @Test
    void legacyResponsesCarryDeprecationSignalling(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            HttpResponse<String> legacy = get(api, "/collectors");
            assertEquals(200, legacy.statusCode(), legacy.body());
            assertEquals("@1783382400", legacy.headers().firstValue("Deprecation").orElse(null),
                    "legacy call must announce its deprecation (RFC 9745)");
            assertEquals("</api/v1>; rel=\"successor-version\"",
                    legacy.headers().firstValue("Link").orElse(null));
            assertTrue(legacy.headers().firstValue("Sunset").isEmpty(),
                    "no Sunset header until an operator signs a date");

            HttpResponse<String> v1 = get(api, "/api/v1/collectors");
            assertEquals(200, v1.statusCode(), v1.body());
            assertTrue(v1.headers().firstValue("Deprecation").isEmpty(), "the v1 surface is not deprecated");

            HttpResponse<String> infra = get(api, "/health");
            assertEquals(200, infra.statusCode());
            assertTrue(infra.headers().firstValue("Deprecation").isEmpty(),
                    "infra probes are always-unversioned by design, never deprecated");
        } finally {
            api.close();
            svc.close();
        }
    }

    @Test
    void sunsetHeaderAppearsOnceADateIsSigned(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        System.setProperty("api.legacy.sunset", "2026-12-31");
        try {
            ControlApi api = new ControlApi(svc, 0);   // property is read at construction
            api.start();
            try {
                HttpResponse<String> legacy = get(api, "/collectors");
                assertEquals(200, legacy.statusCode());
                assertEquals("Thu, 31 Dec 2026 00:00:00 GMT",
                        legacy.headers().firstValue("Sunset").orElse(null));
            } finally {
                api.close();
            }
        } finally {
            System.clearProperty("api.legacy.sunset");
            svc.close();
        }
    }

    @Test
    void offModeRetiresOnlyTheLegacySurface(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        System.setProperty("api.legacy.routes", "off");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                HttpResponse<String> legacy = get(api, "/collectors");
                assertEquals(410, legacy.statusCode(), "legacy business route must be Gone in off mode");
                assertTrue(legacy.body().contains("/api/v1"), legacy.body());

                assertEquals(200, get(api, "/api/v1/collectors").statusCode(), "v1 must be untouched");
                assertEquals(200, get(api, "/health").statusCode(), "infra probes must be untouched");

                // The soak signal keeps counting residual demand through the off-window.
                HttpResponse<String> metrics = get(api, "/metrics");
                assertEquals(200, metrics.statusCode());
                assertTrue(metrics.body().contains("inspecto_legacy_api_requests_total"),
                        "the 410'd call must still increment the sunset metric");
            } finally {
                api.close();
            }
        } finally {
            System.clearProperty("api.legacy.routes");
            svc.close();
        }
    }
}
