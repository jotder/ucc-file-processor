package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.etl.ConfigValidator;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.MultiSourceProcessor;
import com.gamma.inspector.ReprocessCommand;
import com.gamma.service.SourceService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded REST control plane for a running {@link SourceService} (M3). Built on the
 * JDK's {@link HttpServer} (no extra dependencies — keeps the lean fat-JAR) with
 * Jackson for JSON. Every CLI operation has an HTTP equivalent: list/trigger/pause/
 * resume pipelines, query runs/batches/files/lineage/quarantine via the
 * {@link com.gamma.service.StatusStore}, reprocess a batch, and validate a config.
 *
 * <h3>Auth</h3>
 * A bearer token guards every route except {@code /health} and {@code /ready}. Supply
 * it via the constructor (CLI: {@code -Dcontrol.token=...}). Present it as
 * {@code Authorization: Bearer <token>} or {@code X-Api-Token: <token>}. If no token is
 * configured the API runs <b>open</b> (dev only) and logs a warning.
 *
 * <h3>Routes</h3>
 * <pre>
 *   GET  /health                              liveness (open)
 *   GET  /ready                               readiness (open)
 *   GET  /pipelines                           list pipelines + state
 *   POST /pipelines/{name}/trigger            run one pipeline once
 *   POST /pipelines/{name}/pause              pause (poll cycle skips it)
 *   POST /pipelines/{name}/resume             resume
 *   GET  /pipelines/{name}/commits            committed batch ids
 *   GET  /pipelines/{name}/batches            batch audit rows
 *   GET  /pipelines/{name}/files              per-file audit rows
 *   GET  /pipelines/{name}/lineage[?batchId=] input→output lineage rows
 *   GET  /pipelines/{name}/quarantine         quarantined inputs + reason
 *   POST /pipelines/{name}/reprocess          body {"batchId":"…"} — replay a batch
 *   POST /trigger                             run all pipelines once
 *   POST /validate                            body {"configPath":"…"} — config warnings
 * </pre>
 */
@PublicApi(since = "2.4.0")
public final class ControlApi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ControlApi.class);

    private final HttpServer http;
    private final SourceService service;
    private final String token;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Route> routes = new ArrayList<>();

    /**
     * @param service the running service to control
     * @param port    TCP port (0 = ephemeral; read back via {@link #port()})
     * @param token   bearer token; {@code null}/blank runs open (dev only)
     */
    public ControlApi(SourceService service, int port, String token) throws IOException {
        this.service = service;
        this.token   = (token == null || token.isBlank()) ? null : token;
        this.http    = HttpServer.create(new InetSocketAddress(port), 0);
        this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        registerRoutes();
        this.http.createContext("/", this::dispatch);
    }

    public int port() { return http.getAddress().getPort(); }

    public void start() {
        http.start();
        if (token == null)
            log.warn("ControlApi started OPEN (no token) on port {} — set -Dcontrol.token for auth", port());
        else
            log.info("ControlApi started on port {} (token auth enabled)", port());
    }

    @Override
    public void close() {
        http.stop(0);
        log.info("ControlApi stopped");
    }

    // ── CLI ────────────────────────────────────────────────────────────────────

    /**
     * Run the service <em>with</em> the control plane attached:
     * <pre>
     *   java -cp file-processor.jar com.gamma.control.ControlApi \
     *        [-Dcontrol.port=8080] [-Dcontrol.token=secret] \
     *        [-Dservice.poll.seconds=N] [-Dservice.max.runs=M] &lt;config.toon | dir&gt; ...
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ControlApi [-Dcontrol.port=8080] [-Dcontrol.token=secret] "
                    + "[-Dservice.poll.seconds=N] [-Dservice.max.runs=M] <pipeline.toon | dir> [more ...]");
            System.exit(1);
        }
        SourceService svc = SourceService.fromArgs(args);
        int port = Integer.getInteger("control.port", 8080);
        ControlApi api = new ControlApi(svc, port, System.getProperty("control.token"));

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            svc.close();
            latch.countDown();
        }, "ucc-shutdown"));
        svc.start();
        api.start();
        latch.await();   // block until SIGTERM/SIGINT
    }

    // ── routes ───────────────────────────────────────────────────────────────────

    private void registerRoutes() {
        get ("/health", false, (e, m) -> Map.of("status", "UP"));
        get ("/ready",  false, (e, m) -> Map.of("status", "READY", "pipelines", service.pipelines().size()));

        get ("/pipelines", true, (e, m) -> service.pipelines());
        post("/pipelines/([^/]+)/trigger", true, (e, m) ->
                service.runPipeline(name(m)).orElseThrow(() -> notFound(name(m))));
        post("/pipelines/([^/]+)/pause", true, (e, m) -> {
            if (!service.pause(name(m))) throw notFound(name(m));
            return Map.of("pipeline", name(m), "paused", true);
        });
        post("/pipelines/([^/]+)/resume", true, (e, m) -> {
            if (!service.resume(name(m))) throw notFound(name(m));
            return Map.of("pipeline", name(m), "paused", false);
        });

        get("/pipelines/([^/]+)/commits",    true, (e, m) -> service.statusStore().committedBatches(cfg(m)));
        get("/pipelines/([^/]+)/batches",    true, (e, m) -> service.statusStore().batches(cfg(m)));
        get("/pipelines/([^/]+)/files",      true, (e, m) -> service.statusStore().files(cfg(m)));
        get("/pipelines/([^/]+)/lineage",    true, (e, m) -> service.statusStore().lineage(cfg(m), query(e, "batchId")));
        get("/pipelines/([^/]+)/quarantine", true, (e, m) -> service.statusStore().quarantine(cfg(m)));

        post("/pipelines/([^/]+)/reprocess", true, (e, m) -> {
            var path = service.pathFor(name(m)).orElseThrow(() -> notFound(name(m)));
            String batchId = str(body(e), "batchId");
            if (batchId == null) throw new ApiException(400, "body must include 'batchId'");
            ReprocessCommand.run(path.toString(), batchId);
            return Map.of("pipeline", name(m), "batchId", batchId, "status", "reprocessed");
        });

        post("/trigger", true, (e, m) -> service.runAllOnce());

        post("/validate", true, (e, m) -> {
            String configPath = str(body(e), "configPath");
            if (configPath == null) throw new ApiException(400, "body must include 'configPath'");
            PipelineConfig cfg = PipelineConfig.load(configPath);
            List<String> warnings = ConfigValidator.validate(cfg);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pipeline", cfg.identity().pipelineName());
            r.put("warnings", warnings);
            r.put("clean", warnings.isEmpty());
            return r;
        });
    }

    // ── dispatch ───────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange ex) throws IOException {
        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.pattern.matcher(path);
                if (!m.matches()) continue;
                pathMatched = true;
                if (!r.method.equals(method)) continue;
                if (r.auth) requireAuth(ex);
                Object result = r.handler.handle(ex, m);
                respond(ex, 200, result);
                return;
            }
            respond(ex, pathMatched ? 405 : 404,
                    Map.of("error", pathMatched ? "method not allowed" : "not found"));
        } catch (ApiException ae) {
            respond(ex, ae.status, Map.of("error", ae.getMessage()));
        } catch (Exception e) {
            log.error("{} {} failed", method, path, e);
            respond(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    private void requireAuth(HttpExchange ex) {
        if (token == null) return;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String presented = (auth != null && auth.startsWith("Bearer "))
                ? auth.substring("Bearer ".length()).trim()
                : ex.getRequestHeaders().getFirst("X-Api-Token");
        if (!token.equals(presented)) throw new ApiException(401, "unauthorized");
    }

    private void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private PipelineConfig cfg(Matcher m) {
        return service.configFor(name(m)).orElseThrow(() -> notFound(name(m)));
    }

    private static String name(Matcher m) {
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private static ApiException notFound(String name) {
        return new ApiException(404, "no pipeline named '" + name + "'");
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] raw = in.readAllBytes();
            if (raw.length == 0) return Map.of();
            return json.readValue(raw, Map.class);
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key))
                return URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private void get(String pattern, boolean auth, Handler h)  { routes.add(new Route("GET",  Pattern.compile("^" + pattern + "$"), auth, h)); }
    private void post(String pattern, boolean auth, Handler h) { routes.add(new Route("POST", Pattern.compile("^" + pattern + "$"), auth, h)); }

    @FunctionalInterface
    private interface Handler { Object handle(HttpExchange ex, Matcher m) throws Exception; }

    private record Route(String method, Pattern pattern, boolean auth, Handler handler) {}

    /** Maps to an HTTP status + JSON {@code {"error": …}} body. */
    private static final class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
}
