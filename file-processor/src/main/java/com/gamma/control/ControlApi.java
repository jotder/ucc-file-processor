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
 *   GET  /status                              live status snapshot (all pipelines)        [v2.8.0]
 *   GET  /report                              service-wide batch-audit report             [v2.8.0]
 *   GET  /pipelines/{name}/report             batch-audit report for one pipeline         [v2.8.0]
 *   GET  /jobs                                list config-driven jobs + last/next run      [v2.8.0]
 *   GET  /jobs/{name}/runs                    recent run history for a job                 [v2.8.0]
 *   POST /jobs/{name}/trigger                 run a job once now                           [v2.8.0]
 *   GET  /enrichment                          list Stage-2 enrichment jobs + last run      [v2.9.0]
 *   GET  /enrichment/{job}/runs               enrichment run-audit rows                    [v2.9.0]
 *   GET  /enrichment/{job}/lineage[?runId=]   enrichment output lineage rows               [v2.9.0]
 *   GET  /enrichment/{job}/report             run-audit rollup for one enrichment job      [v2.9.0]
 * </pre>
 */
@PublicApi(since = "2.4.0")
public final class ControlApi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ControlApi.class);
    /** Returned by a handler that has already written its own (non-JSON) response. */
    private static final Object HANDLED = new Object();

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
        // Prometheus scrape endpoint — text exposition, open (scrapers don't carry tokens)
        get("/metrics", false, (e, m) ->
                respondText(e, com.gamma.metrics.MetricRegistry.global().scrape()));

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

        // ── v2.8.0: aggregated reports (status snapshot + batch-audit rollup) ──
        get("/status", true, (e, m) -> service.reports().statusReport());
        get("/report", true, (e, m) -> service.reports().serviceReport());
        get("/pipelines/([^/]+)/report", true, (e, m) -> {
            cfg(m);   // 404 if no such pipeline
            return service.reports().batchReport(name(m));
        });

        // ── v2.8.0: config-driven jobs (cron / event / manual) ──
        get("/jobs", true, (e, m) -> jobs().jobs());
        get("/jobs/([^/]+)/runs", true, (e, m) -> jobs().runsFor(name(m)));
        post("/jobs/([^/]+)/trigger", true, (e, m) -> {
            if (!jobs().trigger(name(m)))
                throw new ApiException(404, "no job named '" + name(m) + "'");
            return Map.of("job", name(m), "status", "triggered");
        });

        // ── v2.9.0: Stage-2 enrichment run audit + lineage + rollup ──
        get("/enrichment", true, (e, m) -> enrichment().views());
        get("/enrichment/([^/]+)/runs", true, (e, m) -> enrichment().runs(enrichJob(m)));
        get("/enrichment/([^/]+)/lineage", true, (e, m) ->
                enrichment().lineage(enrichJob(m), query(e, "runId")));
        get("/enrichment/([^/]+)/report", true, (e, m) ->
                service.reports().enrichmentReport(enrichJob(m)));

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
                if (result != HANDLED) respond(ex, 200, result);
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

    /** Write a {@code text/plain} body (Prometheus exposition) and signal it's handled. */
    private Object respondText(HttpExchange ex, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        return HANDLED;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private PipelineConfig cfg(Matcher m) {
        return service.configFor(name(m)).orElseThrow(() -> notFound(name(m)));
    }

    /** The job registry, or a 404 when no jobs are registered on this service. */
    private com.gamma.job.JobService jobs() {
        return service.jobService().orElseThrow(() -> new ApiException(404, "no jobs registered"));
    }

    /** The enrichment service, or a 404 when no enrichment jobs are registered. */
    private com.gamma.service.EnrichmentService enrichment() {
        return service.enrichmentService()
                .orElseThrow(() -> new ApiException(404, "no enrichment jobs registered"));
    }

    /** Resolve a path-named enrichment job to its name, 404 when it is not registered. */
    private String enrichJob(Matcher m) {
        String n = name(m);
        if (enrichment().config(n).isEmpty())
            throw new ApiException(404, "no enrichment job named '" + n + "'");
        return n;
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
