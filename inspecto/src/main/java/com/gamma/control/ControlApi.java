package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.service.SourceService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <h3>Authentication</h3>
 * The core (Personal edition) is <b>auth-free</b> — every route is open. The bearer-token
 * scopes that earlier versions enforced inline have been removed; authentication and
 * authorization are now an <em>edition</em> concern. The Standard / Enterprise editions
 * re-introduce them out-of-band via the security module (OIDC resource-server validating
 * IAM-issued JWTs + RBAC/ABAC), without the core engine carrying any auth code. See
 * {@code docs/EDITIONS.md}.
 *
 * <h3>Routes</h3>
 * <pre>
 *   GET  /health                              liveness (open)
 *   GET  /ready                               readiness (open)
 *   GET  /pipelines                           list pipelines + state
 *   POST /pipelines                           body {"configPath":"…"} — register a new pipeline   [v4.1.0]
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
 *   POST /validate                            body {"configPath":"…"} or {"type":…,"config":{…}} — findings
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
 *   GET  /config/spec/{type}                  declarative spec for a config type           [v3.2.0]
 *   POST /assist/{intent}                     run an assist skill (e.g. explain-entity)    [v3.3.0]
 *   POST /config/write                        body {type,config,subdir?,overwrite?} — persist a config [v4.1.0]
 *   GET  /events[?limit=]                     recent events, newest-first (live tail)       [v4.2.0]
 *   GET  /events/search[?level=&type=&pipeline=&correlationId=&q=&from=&to=&limit=&offset=] filtered events [v4.2.0]
 *   GET  /events/{id}                         one event by id                               [v4.2.0]
 *   GET  /events/export[?format=csv&…filters] export matching events (csv | json)           [v4.2.0]
 *   GET  /events/views                        list operator-saved views                     [v4.2.0]
 *   POST /events/views                        body {name,level?,type?,pipeline?,q?,…} — upsert a view [v4.2.0]
 *   POST /events/views/{name}/delete          delete a saved view                           [v4.2.0]
 *   GET  /objects[?type=&status=&severity=&assignee=&owner=&correlationId=&q=&limit=&offset=] filtered objects [v4.3.0]
 *   POST /objects                             body {type?,title,severity?,priority?,assignee?,dueAt?|dueInMinutes?,…} — create (ISSUE) [v4.4.0]
 *   GET  /objects/{id}                        one object by id                              [v4.3.0]
 *   POST /objects/{id}/ack | /resolve         fixed-action lifecycle transition (ALERT)     [v4.3.0]
 *   POST /objects/{id}/transition             body {action} or {status|to} (+ actor?) — any workflow move [v4.3.0]
 *   POST /objects/{id}/links                  body {to,relationship?,actor?} — correlate two objects (CASE) [v4.5.0]
 *   GET  /objects/{id}/links                  links incident to this object                 [v4.5.0]
 *   GET  /objects/{id}/graph[?depth=]         correlation subgraph (nodes + edges)          [v4.5.0]
 *   POST /objects/{id}/comments               body {body,author?} — add a comment           [v4.6.0]
 *   GET  /objects/{id}/comments               list comments (newest-first)                  [v4.6.0]
 *   POST /objects/{id}/attachments            body {name,uri,contentType?,author?} — evidence ref [v4.6.0]
 *   GET  /objects/{id}/attachments            list attachment references                    [v4.6.0]
 *   POST /objects/{id}/rca                     body {sections[]} | {template:{…}} | {template:"name"} — seed RCA skeleton [v4.6.0]
 *   GET  /rca/templates                       RCA templates loaded from *_rca.toon          [v4.6.0]
 *   GET  /connections                         reusable connection profiles (secret-masked)  [v4.2.0]
 *   GET  /connections/{id}                    one connection profile (secret-masked)        [v4.2.0]
 *   POST /connections/{id}/test               TCP-reachability + secret-resolution test     [v4.2.0]
 * </pre>
 *
 * <p>The {@code /catalog*}, {@code /config/spec/*} and {@code /assist/*} routes require the
 * {@code assist.read} scope (satisfied by {@code control}); they expose the M1 metadata graph, the
 * M2 declarative config specs, and the M3 in-process assist agent for the UI and the agent. The
 * {@code /assist/*} route delegates to the optional {@code file-processor-agent} module when it is
 * on the classpath; with no agent present it returns {@code 503}, leaving the core unchanged.</p>
 *
 * <p>Report routes accept an optional inclusive date range {@code ?from=&to=} (v2.10.0) —
 * a date ({@code 2026-05-01}) or datetime ({@code 2026-05-01 09:00:00}); a date-only
 * {@code to} covers the whole day. Reports also carry duration percentiles (p50/p95/p99).
 *
 * <h3>UI hosting (v4.1.0)</h3>
 * Two optional, pure-JDK additions let a single process serve both the JSON API and the operator
 * SPA, with no new dependencies:
 * <ul>
 *   <li><b>CORS</b> — set {@code -Dcontrol.cors=<origin>} (e.g. {@code http://localhost:4200}, or
 *       {@code *}) to emit {@code Access-Control-Allow-*} headers and answer {@code OPTIONS}
 *       preflights with {@code 204}. Unset (the default) ⇒ behaviour is byte-for-byte unchanged.</li>
 *   <li><b>Static SPA</b> — set {@code -Dui.dir=<dir>} to serve the built Angular app from disk as a
 *       {@code PUBLIC} fallback for any {@code GET} that matches no API route. A request for a file
 *       that exists is served with its MIME type; an extensionless path (an SPA deep link) falls back
 *       to {@code index.html}. API paths that match a route keep returning JSON (incl. JSON 404s).</li>
 * </ul>
 */
@PublicApi(since = "2.4.0")
public final class ControlApi implements AutoCloseable, ApiContext {

    private static final Logger log = LoggerFactory.getLogger(ControlApi.class);
    private static final Object HANDLED = ApiContext.HANDLED;

    private final HttpServer http;
    private final SourceService service;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Route> routes = new ArrayList<>();
    /** Allowed CORS origin ({@code -Dcontrol.cors}); {@code null} ⇒ CORS disabled (default). */
    private final String corsOrigin;
    /** Static SPA root ({@code -Dui.dir}); {@code null} ⇒ no static serving (default). */
    private final Path uiDir;
    /**
     * Filesystem root under which {@code POST /config/write} may persist authored configs
     * ({@code -Dassist.write.root}); {@code null} ⇒ config writes are disabled (the route returns
     * 503). Made absolute + normalised so the write path-jail ({@code startsWith}) is meaningful.
     */
    private final Path writeRoot;

    /**
     * @param service the running service to control
     * @param port    TCP port (0 = ephemeral; read back via {@link #port()})
     */
    public ControlApi(SourceService service, int port) throws IOException {
        this.service = service;
        String cors  = System.getProperty("control.cors");
        this.corsOrigin = blank(cors) ? null : cors.trim();
        String ui    = System.getProperty("ui.dir");
        this.uiDir   = blank(ui) ? null : Path.of(ui.trim()).toAbsolutePath().normalize();
        String wr    = System.getProperty("assist.write.root");
        this.writeRoot = blank(wr) ? null : Path.of(wr.trim()).toAbsolutePath().normalize();
        this.http    = HttpServer.create(new InetSocketAddress(port), 0);
        this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        registerRoutes();
        this.http.createContext("/", this::dispatch);
    }

    public int port() { return http.getAddress().getPort(); }

    public void start() {
        http.start();
        log.info("ControlApi started on port {} (no authentication — Personal/core edition). "
                + "Authorization is added by the Standard/Enterprise security module.", port());
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
     *        [-Dcontrol.port=8080] \
     *        [-Dservice.poll.seconds=N] [-Dservice.max.runs=M] &lt;config.toon | dir&gt; ...
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ControlApi [-Dcontrol.port=8080] "
                    + "[-Dservice.poll.seconds=N] [-Dservice.max.runs=M] <pipeline.toon | dir> [more ...]");
            System.exit(1);
        }
        SourceService svc = SourceService.fromArgs(args);
        int port = Integer.getInteger("control.port", 8080);
        ControlApi api = new ControlApi(svc, port);

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            svc.close();
            latch.countDown();
        }, "inspecto-shutdown"));
        svc.start();
        api.start();
        latch.await();   // block until SIGTERM/SIGINT
    }

    // ── routes ───────────────────────────────────────────────────────────────────

    private void registerRoutes() {
        get ("/health", (e, m) -> Map.of("status", "UP"));
        get ("/ready",  (e, m) -> Map.of("status", "READY", "pipelines", service.pipelines().size()));
        // Prometheus scrape endpoint — text exposition, open (scrapers don't carry tokens)
        get("/metrics", (e, m) ->
                respondText(e, com.gamma.metrics.MetricRegistry.global().scrape()));

        // Feature route modules extracted from this class (see RouteModule); each owns its own routes + docs.
        for (RouteModule module : List.of(
                new PipelineRoutes(),
                new ConnectionRoutes(), new ViewRoutes(), new FlowRoutes(), new ComponentRoutes(),
                new EventRoutes(), new ObjectRoutes(), new CatalogRoutes(), new ConfigRoutes(),
                new JobRoutes(), new EnrichmentRoutes(), new AlertRoutes(), new AcquisitionRoutes(),
                new AssistRoutes()))
            module.register(this);
    }

    // ── dispatch ───────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange ex) throws IOException {
        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        // Accept an optional "/api" prefix so a single SPA build works in both deployment modes:
        // behind the ng-serve dev proxy (which rewrites "/api" → "") and when served same-origin by
        // ControlApi itself (no proxy). The Angular app addresses every route as "/api/...", so strip
        // the prefix here before route matching. Static assets never carry "/api", so they're untouched.
        if (path.startsWith("/api/")) path = path.substring(4);
        else if (path.equals("/api")) path = "/";
        if (corsOrigin != null) applyCors(ex);     // rides every response written below
        try {
            // CORS preflight: answer before route matching (no token, no body).
            if (corsOrigin != null && "OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.pattern.matcher(path);
                if (!m.matches()) continue;
                pathMatched = true;
                if (!r.method.equals(method)) continue;
                Object result = r.handler.handle(ex, m);
                if (result != HANDLED) respond(ex, 200, result);
                return;
            }
            // No API route matched the path: a GET may be an SPA asset / deep link (PUBLIC).
            if (!pathMatched && "GET".equals(method) && serveStatic(ex, path)) return;
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

    private void respond(HttpExchange ex, int status, Object body) throws IOException {
        ApiContext.respondJson(ex, status, body);
    }

    /** Write a {@code text/plain} body (Prometheus exposition) and signal it's handled. */
    private Object respondText(HttpExchange ex, String text) throws IOException {
        return respondText(ex, text, "text/plain; version=0.0.4; charset=utf-8");
    }

    /** Write {@code text} with an explicit {@code Content-Type} (e.g. {@code text/csv}); returns {@link #HANDLED}. */
    private Object respondText(HttpExchange ex, String text, String contentType) throws IOException {
        return ApiContext.respondText(ex, text, contentType);
    }

    // ── CORS + static SPA (v4.1.0) ────────────────────────────────────────────────

    /** Emit permissive CORS headers for the configured origin (set once per exchange in dispatch). */
    private void applyCors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", corsOrigin);
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Token");
        h.set("Access-Control-Max-Age", "600");
        if (!"*".equals(corsOrigin)) h.set("Vary", "Origin");
    }

    /**
     * Serve a file from the {@code -Dui.dir} SPA root (PUBLIC). Returns {@code false} (caller emits a
     * JSON 404) when no static root is configured or the request resolves to no servable file. An
     * extensionless path with no matching file falls back to {@code index.html} so client-side
     * routing (deep links) works. Path traversal is blocked by confining the resolved path under the
     * root.
     */
    private boolean serveStatic(HttpExchange ex, String path) throws IOException {
        if (uiDir == null) return false;
        String rel = path.equals("/") ? "index.html" : path.substring(1);   // strip leading '/'
        Path target = uiDir.resolve(rel).normalize();
        if (!target.startsWith(uiDir)) return false;                         // traversal guard
        if (Files.isRegularFile(target)) { writeFile(ex, target); return true; }
        if (!hasExtension(rel)) {                                            // SPA deep link
            Path index = uiDir.resolve("index.html");
            if (Files.isRegularFile(index)) { writeFile(ex, index); return true; }
        }
        return false;
    }

    private void writeFile(HttpExchange ex, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    /** True when the last path segment carries a file extension (e.g. {@code main.js}, not {@code dashboard}). */
    private static boolean hasExtension(String rel) {
        int slash = rel.lastIndexOf('/');
        return rel.lastIndexOf('.') > slash;
    }

    /** Minimal extension→MIME map for the static SPA assets we actually ship. */
    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "html"          -> "text/html; charset=utf-8";
            case "js", "mjs"     -> "text/javascript; charset=utf-8";
            case "css"           -> "text/css; charset=utf-8";
            case "json", "map"   -> "application/json";
            case "svg"           -> "image/svg+xml";
            case "ico"           -> "image/x-icon";
            case "png"           -> "image/png";
            case "jpg", "jpeg"   -> "image/jpeg";
            case "gif"           -> "image/gif";
            case "webp"          -> "image/webp";
            case "woff2"         -> "font/woff2";
            case "woff"          -> "font/woff";
            case "ttf"           -> "font/ttf";
            case "txt"           -> "text/plain; charset=utf-8";
            default              -> "application/octet-stream";
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] raw = in.readAllBytes();
            if (raw.length == 0) return Map.of();
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        }
    }

    @Override
    public SourceService service() { return service; }

    @Override
    public Path writeRoot() { return writeRoot; }

    // Route registration. The core (Personal edition) is auth-free — every route is open.
    // Standard/Enterprise editions re-introduce authorization out-of-band via the security module.
    @Override public void get (String pattern, Handler h) { routes.add(new Route("GET",    Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void post(String pattern, Handler h) { routes.add(new Route("POST",   Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void put   (String pattern, Handler h) { routes.add(new Route("PUT",    Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void delete(String pattern, Handler h) { routes.add(new Route("DELETE", Pattern.compile("^" + pattern + "$"), h)); }

    private record Route(String method, Pattern pattern, Handler handler) {}
}
