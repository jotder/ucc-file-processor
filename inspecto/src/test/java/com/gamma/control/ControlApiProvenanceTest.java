package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The data-plane provenance query routes (T22) over real HTTP: param validation and the default-off gate.
 * (The stored counts themselves are exercised by {@code FlowJobRunnerTest} against a real {@code DbProvenanceStore};
 * here we only confirm the routes are wired, validated, and 404 when no provenance backend is configured.)
 */
class ControlApiProvenanceTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void validatesParamsAndGatesOnTheBackend(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // missing required params → 400 (checked before the store is consulted)
            assertEquals(400, get(c.port, "/provenance").statusCode());
            assertEquals(400, get(c.port, "/provenance?flow=f").statusCode());
            assertEquals(400, get(c.port, "/provenance/batches").statusCode());

            // params present but no provenance backend configured → 404 (default-off)
            assertEquals(404, get(c.port, "/provenance?flow=f&batch=b").statusCode());
            assertEquals(404, get(c.port, "/provenance/batches?flow=f").statusCode());
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("GET", BodyPublishers.noBody()).build();
        return client.send(req, BodyHandlers.ofString());
    }
}
