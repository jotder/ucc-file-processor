package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.catalog.EdgeKind;
import com.gamma.catalog.MetadataEdge;
import com.gamma.catalog.MetadataGraph;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <h3>Auth (v3.0: scoped + fail-closed)</h3>
 * Routes carry a {@link Scope}. {@code /health}, {@code /ready} and {@code /metrics} are
 * {@code PUBLIC} (no token). Every other current route requires the {@code CONTROL} scope.
 * Tokens are supplied per scope via {@link Tokens} (CLI: {@code -Dcontrol.token},
 * {@code -Dassist.read.token}, {@code -Dassist.write.token}); present one as
 * {@code Authorization: Bearer <token>} or {@code X-Api-Token: <token>}. Scopes are
 * hierarchical — {@code CONTROL} satisfies any scope; {@code assist.write} satisfies
 * {@code assist.read}. Comparison is constant-time ({@link MessageDigest#isEqual}).
 *
 * <p><b>Fail-closed:</b> unlike 2.x there is no open-by-default mode. If a scope has no
 * token configured, its routes return {@code 401} (locked) rather than running open. Set
 * {@code -Dcontrol.token} to use the control plane. The {@code assist.*} scopes back the
 * {@code /assist/*} routes that arrive in M2.
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
 *   GET  /report[?from=&to=]                  service-wide batch-audit report             [v2.8.0]
 *   GET  /pipelines/{name}/report[?from=&to=] batch-audit report for one pipeline         [v2.8.0]
 *   GET  /jobs                                list config-driven jobs + last/next run      [v2.8.0]
 *   GET  /jobs/{name}/runs                    recent run history for a job                 [v2.8.0]
 *   POST /jobs/{name}/trigger                 run a job once now                           [v2.8.0]
 *   GET  /enrichment                          list Stage-2 enrichment jobs + last run      [v2.9.0]
 *   GET  /enrichment/{job}/runs               enrichment run-audit rows                    [v2.9.0]
 *   GET  /enrichment/{job}/lineage[?runId=]   enrichment output lineage rows               [v2.9.0]
 *   GET  /enrichment/{job}/report[?from=&to=] run-audit rollup for one enrichment job      [v2.9.0]
 *   GET  /catalog                             metadata-graph table list                    [v3.2.0]
 *   GET  /catalog/tables/{id}                 one node + overlay + neighbours              [v3.2.0]
 *   GET  /catalog/kpis                        KPI catalog + domain notes                   [v3.2.0]
 *   GET  /catalog/graph[?from=&depth=&direction=&kinds=&edgeKinds=&overlay=]  subgraph      [v3.2.0]
 * </pre>
 *
 * <p>The {@code /catalog*} routes require the {@code assist.read} scope (satisfied by
 * {@code control}); they expose the M2 metadata graph for the UI and assist agent.</p>
 *
 * <p>Report routes accept an optional inclusive date range {@code ?from=&to=} (v2.10.0) —
 * a date ({@code 2026-05-01}) or datetime ({@code 2026-05-01 09:00:00}); a date-only
 * {@code to} covers the whole day. Reports also carry duration percentiles (p50/p95/p99).
 */
@PublicApi(since = "2.4.0")
public final class ControlApi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ControlApi.class);
    /** Returned by a handler that has already written its own (non-JSON) response. */
    private static final Object HANDLED = new Object();

    private final HttpServer http;
    private final SourceService service;
    private final Tokens tokens;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Route> routes = new ArrayList<>();

    /** Authorization scope for a route (v3.0). {@link #PUBLIC} routes need no token. */
    @PublicApi(since = "3.0.0")
    public enum Scope {
        PUBLIC("public"), CONTROL("control"), ASSIST_READ("assist.read"), ASSIST_WRITE("assist.write");
        final String label;
        Scope(String label) { this.label = label; }
    }

    /**
     * Per-scope bearer tokens (v3.0). A {@code null}/blank token <b>locks</b> that scope
     * (fail-closed — its routes return 401). The control token is the superuser: it
     * satisfies every scope. {@code assistWrite} also satisfies {@code assist.read}.
     */
    @PublicApi(since = "3.0.0")
    public record Tokens(String control, String assistRead, String assistWrite) {
        /** Only the control plane is enabled; assist scopes are locked. */
        public static Tokens controlOnly(String control) { return new Tokens(control, null, null); }
        /** Read {@code -Dcontrol.token} / {@code -Dassist.read.token} / {@code -Dassist.write.token}. */
        public static Tokens fromSystemProperties() {
            return new Tokens(System.getProperty("control.token"),
                              System.getProperty("assist.read.token"),
                              System.getProperty("assist.write.token"));
        }
    }

    /**
     * Convenience constructor: a single control token (assist scopes locked). Equivalent to
     * {@code new ControlApi(service, port, Tokens.controlOnly(token))}.
     *
     * @param service the running service to control
     * @param port    TCP port (0 = ephemeral; read back via {@link #port()})
     * @param token   the {@code CONTROL}-scope bearer token; {@code null}/blank locks control routes
     */
    public ControlApi(SourceService service, int port, String token) throws IOException {
        this(service, port, Tokens.controlOnly(token));
    }

    /**
     * @param service the running service to control
     * @param port    TCP port (0 = ephemeral; read back via {@link #port()})
     * @param tokens  per-scope bearer tokens; a locked scope (null token) fails closed (401)
     */
    public ControlApi(SourceService service, int port, Tokens tokens) throws IOException {
        this.service = service;
        this.tokens  = tokens != null ? tokens : new Tokens(null, null, null);
        this.http    = HttpServer.create(new InetSocketAddress(port), 0);
        this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        registerRoutes();
        this.http.createContext("/", this::dispatch);
    }

    public int port() { return http.getAddress().getPort(); }

    public void start() {
        http.start();
        String control = tokens.control();
        if (control == null || control.isBlank())
            log.warn("ControlApi on port {}: no control token — CONTROL routes are LOCKED (401). "
                    + "Set -Dcontrol.token to enable them. Public: /health, /ready, /metrics", port());
        else
            log.info("ControlApi started on port {} (scoped bearer auth: control{}{})", port(),
                    blank(tokens.assistRead())  ? "" : " +assist.read",
                    blank(tokens.assistWrite()) ? "" : " +assist.write");
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

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
        ControlApi api = new ControlApi(svc, port, Tokens.fromSystemProperties());

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
        // v2.10.0: ?from=&to= scope the rollup to a date range (inclusive; date or datetime).
        get("/status", true, (e, m) -> service.reports().statusReport());
        get("/report", true, (e, m) -> service.reports().serviceReport(window(e)));
        get("/pipelines/([^/]+)/report", true, (e, m) -> {
            cfg(m);   // 404 if no such pipeline
            return service.reports().batchReport(name(m), window(e));
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
                service.reports().enrichmentReport(enrichJob(m), window(e)));

        // ── v3.2.0: metadata graph / data catalog (scope assist.read; control satisfies it) ──
        get("/catalog", Scope.ASSIST_READ, (e, m) -> service.catalog().tables());
        get("/catalog/kpis", Scope.ASSIST_READ, (e, m) -> catalogKpis());
        get("/catalog/graph", Scope.ASSIST_READ, (e, m) -> service.catalog().traverse(
                query(e, "from"),
                parseIntOr(query(e, "depth"), 1),
                direction(query(e, "direction")),
                nodeKinds(query(e, "kinds")),
                edgeKinds(query(e, "edgeKinds")),
                "true".equalsIgnoreCase(query(e, "overlay"))));
        get("/catalog/tables/(.+)", Scope.ASSIST_READ, (e, m) -> catalogNodeDetail(name(m)));

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
                if (r.scope != Scope.PUBLIC) requireAuth(r.scope, ex);
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

    /**
     * Enforce the route's {@link Scope}. Fail-closed: a scope with no configured token is
     * locked (401), never open. The presented token is matched constant-time
     * ({@link MessageDigest#isEqual}) against every token that satisfies the scope under the
     * hierarchy (control ⊇ assist.write ⊇ assist.read).
     */
    private void requireAuth(Scope scope, HttpExchange ex) {
        List<String> acceptable = acceptableTokens(scope);
        if (acceptable.isEmpty())
            throw new ApiException(401, "unauthorized: no " + scope.label + " token configured");
        String presented = presentedToken(ex);
        if (presented == null) throw new ApiException(401, "unauthorized");
        byte[] p = presented.getBytes(StandardCharsets.UTF_8);
        for (String t : acceptable)
            if (MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), p)) return;
        throw new ApiException(401, "unauthorized");
    }

    /** Tokens that satisfy a scope, widest-privilege first; empty ⇒ scope is locked. */
    private List<String> acceptableTokens(Scope scope) {
        List<String> out = new ArrayList<>();
        switch (scope) {
            case CONTROL      -> addToken(out, tokens.control());
            case ASSIST_WRITE -> { addToken(out, tokens.assistWrite()); addToken(out, tokens.control()); }
            case ASSIST_READ  -> { addToken(out, tokens.assistRead()); addToken(out, tokens.assistWrite());
                                   addToken(out, tokens.control()); }
            case PUBLIC       -> { /* no token required */ }
        }
        return out;
    }

    private static void addToken(List<String> list, String t) {
        if (t != null && !t.isBlank()) list.add(t);
    }

    /** The bearer token presented on the request ({@code Authorization: Bearer} or {@code X-Api-Token}). */
    private static String presentedToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        return (auth != null && auth.startsWith("Bearer "))
                ? auth.substring("Bearer ".length()).trim()
                : ex.getRequestHeaders().getFirst("X-Api-Token");
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

    // ── catalog helpers (v3.2.0) ─────────────────────────────────────────────────

    /** A node (any kind) with its operational overlay + immediate neighbours, or 404. */
    private Map<String, Object> catalogNodeDetail(String id) {
        MetadataGraphService catalog = service.catalog();
        MetadataNode node = catalog.hydrated(id);
        if (node == null) throw new ApiException(404, "no catalog node '" + id + "'");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        // depth 2 reaches an event table's schema (1) and its columns (2), plus lineage neighbours
        out.put("neighbors", catalog.traverse(id, 2, MetadataGraphService.Direction.BOTH, null, null, false));
        return out;
    }

    /** The KPI catalog (each KPI with its resolved inputs) + merged domain notes. */
    private Map<String, Object> catalogKpis() {
        MetadataGraphService catalog = service.catalog();
        MetadataGraph g = catalog.structural();
        List<Map<String, Object>> kpis = new ArrayList<>();
        for (MetadataNode k : catalog.nodesOfKind(NodeKind.KPI)) {
            List<String> inputs = new ArrayList<>();
            for (MetadataEdge edge : g.edges()) {
                if (edge.kind() == EdgeKind.COMPUTED_FROM && edge.from().equals(k.id())) inputs.add(edge.to());
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", k.id());
            e.put("name", k.label());
            e.put("definition", k.attrs().get("definition"));
            e.put("grain", k.attrs().get("grain"));
            e.put("joinKeys", k.attrs().getOrDefault("joinKeys", List.of()));
            e.put("inputs", inputs);
            kpis.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis);
        out.put("domain", catalog.domain());
        return out;
    }

    private static int parseIntOr(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static MetadataGraphService.Direction direction(String s) {
        if (s == null || s.isBlank()) return MetadataGraphService.Direction.BOTH;
        try {
            return MetadataGraphService.Direction.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid direction '" + s + "' (out|in|both)");
        }
    }

    private static Set<NodeKind> nodeKinds(String csv) {
        if (csv == null || csv.isBlank()) return null;
        EnumSet<NodeKind> set = EnumSet.noneOf(NodeKind.class);
        for (String t : csv.split(",")) {
            if (t.isBlank()) continue;
            try {
                set.add(NodeKind.valueOf(t.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "invalid node kind '" + t.trim() + "'");
            }
        }
        return set;
    }

    private static Set<EdgeKind> edgeKinds(String csv) {
        if (csv == null || csv.isBlank()) return null;
        EnumSet<EdgeKind> set = EnumSet.noneOf(EdgeKind.class);
        for (String t : csv.split(",")) {
            if (t.isBlank()) continue;
            try {
                set.add(EdgeKind.valueOf(t.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "invalid edge kind '" + t.trim() + "'");
            }
        }
        return set;
    }

    /** Build a report {@link com.gamma.report.ReportService.Window} from {@code ?from=&to=}. */
    private static com.gamma.report.ReportService.Window window(HttpExchange ex) {
        return com.gamma.report.ReportService.Window.of(query(ex, "from"), query(ex, "to"));
    }

    private static ApiException notFound(String name) {
        return new ApiException(404, "no pipeline named '" + name + "'");
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] raw = in.readAllBytes();
            if (raw.length == 0) return Map.of();
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
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

    // Scope-typed registration (used by M2 assist routes). The boolean overloads below
    // keep every existing route registration unchanged: false → PUBLIC, true → CONTROL.
    private void get (String pattern, Scope scope, Handler h) { routes.add(new Route("GET",  Pattern.compile("^" + pattern + "$"), scope, h)); }
    private void post(String pattern, Scope scope, Handler h) { routes.add(new Route("POST", Pattern.compile("^" + pattern + "$"), scope, h)); }
    private void get (String pattern, boolean auth, Handler h)  { get (pattern, auth ? Scope.CONTROL : Scope.PUBLIC, h); }
    private void post(String pattern, boolean auth, Handler h)  { post(pattern, auth ? Scope.CONTROL : Scope.PUBLIC, h); }

    @FunctionalInterface
    private interface Handler { Object handle(HttpExchange ex, Matcher m) throws Exception; }

    private record Route(String method, Pattern pattern, Scope scope, Handler handler) {}

    /** Maps to an HTTP status + JSON {@code {"error": …}} body. */
    private static final class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
}
