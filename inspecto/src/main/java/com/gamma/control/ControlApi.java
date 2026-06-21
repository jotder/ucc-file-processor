package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        get ("/pipelines", (e, m) -> service.pipelines());
        // Register a new pipeline from a config on disk under the write root (control scope).
        post("/pipelines", (e, m) -> createPipeline(e, body(e)));
        post("/pipelines/([^/]+)/trigger", (e, m) ->
                service.runPipeline(name(m)).orElseThrow(() -> notFound(name(m))));
        post("/pipelines/([^/]+)/pause", (e, m) -> {
            if (!service.pause(name(m))) throw notFound(name(m));
            return Map.of("pipeline", name(m), "paused", true);
        });
        post("/pipelines/([^/]+)/resume", (e, m) -> {
            if (!service.resume(name(m))) throw notFound(name(m));
            return Map.of("pipeline", name(m), "paused", false);
        });

        get("/pipelines/([^/]+)/commits",    (e, m) -> service.statusStore().committedBatches(cfg(m)));
        get("/pipelines/([^/]+)/batches",    (e, m) -> service.statusStore().batches(cfg(m)));
        get("/pipelines/([^/]+)/files",      (e, m) -> service.statusStore().files(cfg(m)));
        get("/pipelines/([^/]+)/lineage",    (e, m) -> service.statusStore().lineage(cfg(m), query(e, "batchId")));
        get("/pipelines/([^/]+)/quarantine", (e, m) -> service.statusStore().quarantine(cfg(m)));
        // Inbox/processing status: files still pending (matched, not yet processed) + whether the
        // pipeline is currently ingesting. Complements the audit-backed /files (processed history).
        get("/pipelines/([^/]+)/pending",    (e, m) ->
                service.inboxStatus(name(m)).orElseThrow(() -> notFound(name(m))));

        post("/pipelines/([^/]+)/reprocess", (e, m) -> {
            var path = service.pathFor(name(m)).orElseThrow(() -> notFound(name(m)));
            String batchId = str(body(e), "batchId");
            if (batchId == null) throw new ApiException(400, "body must include 'batchId'");
            ReprocessCommand.run(path.toString(), batchId);
            return Map.of("pipeline", name(m), "batchId", batchId, "status", "reprocessed");
        });

        post("/trigger", (e, m) -> service.runAllOnce());

        // ── v2.8.0: aggregated reports (status snapshot + batch-audit rollup) ──
        // v2.10.0: ?from=&to= scope the rollup to a date range (inclusive; date or datetime).
        get("/status", (e, m) -> service.reports().statusReport());
        get("/report", (e, m) -> service.reports().serviceReport(window(e)));
        get("/pipelines/([^/]+)/report", (e, m) -> {
            cfg(m);   // 404 if no such pipeline
            return service.reports().batchReport(name(m), window(e));
        });

        // ── v2.9.0: Stage-2 enrichment run audit + lineage + rollup ──
        get("/enrichment", (e, m) -> enrichment().views());
        get("/enrichment/([^/]+)/runs", (e, m) -> enrichment().runs(enrichJob(m)));
        get("/enrichment/([^/]+)/lineage", (e, m) ->
                enrichment().lineage(enrichJob(m), query(e, "runId")));
        get("/enrichment/([^/]+)/report", (e, m) ->
                service.reports().enrichmentReport(enrichJob(m), window(e)));


        // ── v3.3.0: embedded assist agent — POST /assist/{intent} (scope assist.read) ──
        // ── v3.7.0: recent failure diagnoses (read-only) — registered before the POST catch-all ──
        get("/assist/diagnoses", (e, m) ->
                service.assistAgent()
                        .map(a -> (Object) a.recentDiagnoses(parseIntOr(query(e, "limit"), 50)))
                        .orElse(List.of()));
        // ── v4.1 (B5): alert execution engine — operator-saved *_alert.toon rules evaluated against
        // the batches ledger. Read-only listings + a manual evaluation sweep; the engine itself is
        // event-driven off the batch bus and lives in the lean core (no agent required). ──
        get("/alerts", (e, m) -> service.alertService()
                .map(a -> (Object) a.recent(parseIntOr(query(e, "limit"), 50)))
                .orElse(List.of()));
        get("/alerts/rules", (e, m) -> service.alertService()
                .map(a -> (Object) a.rules())
                .orElse(List.of()));
        post("/alerts/evaluate", (e, m) -> service.alertService()
                .map(a -> (Object) a.evaluateAll())
                .orElseThrow(() -> new ApiException(503,
                        "alert engine not armed (no *_alert.toon rules loaded)")));

        // ── Acquisition / Sources UI: a flat view of every pipeline's source acquisition config +
        // a JSON acquisition-metrics snapshot (the Prometheus /metrics is text-only). CONTROL-scoped. ──
        get("/sources", (e, m) -> service.sources());
        get("/metrics/acquisition", (e, m) -> acquisitionMetrics());


        // Feature route modules extracted from this class (see RouteModule); each owns its own routes + docs.
        for (RouteModule module : List.of(
                new ConnectionRoutes(), new ViewRoutes(), new FlowRoutes(), new ComponentRoutes(),
                new EventRoutes(), new ObjectRoutes(), new CatalogRoutes(), new ConfigRoutes(),
                new JobRoutes()))
            module.register(this);

        // ── v4.1: assist model-provider settings (masked read / validated write / round-trip test).
        // Registered BEFORE the intent catch-all so "settings" never resolves as a skill intent. ──
        get("/assist/settings", (e, m) -> assistAgentOr503().settings());
        get("/assist/metrics", (e, m) -> assistAgentOr503().metrics());
        post("/assist/settings/test", (e, m) -> assistAgentOr503().testSettings());
        post("/assist/settings", (e, m) -> {
            try {
                return assistAgentOr503().updateSettings(body(e));
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, ex.getMessage());
            }
        });
        post("/assist/(.+)", (e, m) -> assist(name(m), body(e)));
    }

    /**
     * Dispatch one assist request to the in-process {@link AssistAgent} (v3.3.0). The {@code intent}
     * (path segment) selects the skill; the JSON body supplies {@code screenContext},
     * {@code partialInput}, and {@code userText}. The agent lives in the optional
     * {@code file-processor-agent} module — core holds only this seam — so the agent may be absent.
     *
     * <p>Status mapping (fail-safe, never throws to the model): no agent on the classpath → 503;
     * an unknown intent ({@link AssistResult.Status#UNSUPPORTED}) → 404; a skill whose model is
     * unavailable ({@link AssistResult.Status#UNAVAILABLE}) → 503 with its message; otherwise the
     * {@link AssistResult} is returned as JSON (200).
     */
    /** The in-process assist agent, or 503 when the optional module is absent (v4.1 settings routes). */
    private AssistAgent assistAgentOr503() {
        return service.assistAgent().orElseThrow(() -> new ApiException(503,
                "assist agent not available (file-processor-agent not on classpath)"));
    }

    private Object assist(String intent, Map<String, Object> body) {
        Optional<AssistAgent> agent = service.assistAgent();
        if (agent.isEmpty())
            throw new ApiException(503, "assist agent not available (file-processor-agent not on classpath)");
        AssistRequest req = new AssistRequest(
                intent, mapField(body, "screenContext"), mapField(body, "partialInput"), str(body, "userText"));
        AssistResult result = agent.get().assist(req);
        return switch (result.status()) {
            case UNSUPPORTED -> throw new ApiException(404, "unknown assist intent: " + intent);
            case UNAVAILABLE -> throw new ApiException(503,
                    result.message() == null ? "assist model unavailable" : result.message());
            case OK -> result;
        };
    }

    /** A nested JSON object from a request body as a {@code Map}, or an empty map when absent/not an object. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return (v instanceof Map<?, ?> map) ? (Map<String, Object>) map : Map.of();
    }

    /**
     * Register a new pipeline from a config already on disk under the write root (v4.1.0, scope
     * {@code control}). Pairs with {@code POST /config/write}: author + persist a {@code .toon}
     * there, then register it so the running service processes it on the next poll cycle — no
     * restart. (Registration is in-memory; a registered pipeline survives a restart only if its
     * file also lies under a config dir the service is launched with — keep {@code assist.write.root}
     * inside the launched config tree to get both.)
     *
     * <p>Body {@code {"configPath":"…"}} — absolute, or relative to {@code -Dassist.write.root}.
     * Gated fail-closed: registration disabled unless the write root is set → 503; missing
     * {@code configPath} → 400; a path resolving outside the root → 403; no file there → 404; a
     * config that fails spec / hard-fail safety (R6) validation → 422 (findings returned); an id
     * colliding with a <em>different</em> registered pipeline → 409. On success the new pipeline's
     * {@link SourceService.PipelineView} is returned.
     */
    private Object createPipeline(HttpExchange ex, Map<String, Object> body) throws IOException {
        if (writeRoot == null)
            throw new ApiException(503, "pipeline registration disabled: set -Dassist.write.root to enable");
        String configPath = str(body, "configPath");
        if (configPath == null || configPath.isBlank())
            throw new ApiException(400, "body must include 'configPath'");

        Path candidate = Path.of(configPath.trim());
        Path resolved = (candidate.isAbsolute() ? candidate : writeRoot.resolve(candidate)).normalize();
        if (!resolved.startsWith(writeRoot))
            throw new ApiException(403, "configPath escapes the write root");
        if (!Files.isRegularFile(resolved))
            throw new ApiException(404, "no config file at "
                    + writeRoot.relativize(resolved).toString().replace('\\', '/'));

        // Validate before registering: spec + the hard-fail safety gate (R6). Block on ERRORs —
        // the file may have been placed here without going through POST /config/write.
        Map<String, Object> raw;
        try {
            raw = ConfigLoader.filesystem().decode(resolved.toString());
        } catch (RuntimeException parse) {
            throw new ApiException(422, "config does not parse: " + parse.getMessage());
        }
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), raw));
        findings.addAll(ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.defaultPolicy()));
        // ERROR here: registration loads the config for real, so an unresolvable schema_file is a
        // guaranteed failure — block with a structured, field-anchored finding instead of letting
        // PipelineConfig.load() surface it as an opaque "config is not a valid pipeline" 422.
        findings.addAll(ConfigRoutes.schemaFileFindings("pipeline", raw, Severity.ERROR));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            respond(ex, 422, Map.of("registered", false,
                    "error", "config has ERROR-level findings; not registered", "findings", findings));
            return HANDLED;
        }

        String id;
        try {
            id = service.registerPipeline(resolved);
        } catch (IllegalStateException collision) {
            throw new ApiException(409, collision.getMessage());
        } catch (RuntimeException invalid) {
            throw new ApiException(422, "config is not a valid pipeline: " + invalid.getMessage());
        }

        SourceService.PipelineView view = service.pipelines().stream()
                .filter(p -> p.name().equals(id)).findFirst().orElse(null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("registered", true);
        r.put("id", id);
        r.put("path", writeRoot.relativize(resolved).toString().replace('\\', '/'));
        r.put("pipeline", view);
        r.put("findings", findings);   // warnings only at this point
        return r;
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

    private PipelineConfig cfg(Matcher m) {
        return service.configFor(name(m)).orElseThrow(() -> notFound(name(m)));
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
        return ApiContext.name(m);
    }

    private static String param(Matcher m, int g) {
        return ApiContext.param(m, g);
    }

    private static int parseIntOr(String s, int def) {
        return ApiContext.parseIntOr(s, def);
    }

    /** Build a report {@link com.gamma.report.ReportService.Window} from {@code ?from=&to=}. */
    private static com.gamma.report.ReportService.Window window(HttpExchange ex) {
        return com.gamma.report.ReportService.Window.of(query(ex, "from"), query(ex, "to"));
    }

    /** Acquisition metric names exposed (as JSON) by {@code GET /metrics/acquisition}. */
    private static final java.util.Set<String> ACQ_METRICS = java.util.Set.of(
            "inspecto_files_discovered_total", "inspecto_files_downloaded_total", "inspecto_downloads_failed_total",
            "inspecto_post_actions_failed_total", "inspecto_watermark_skipped_total", "inspecto_bytes_transferred_total",
            "inspecto_fetch_seconds", "inspecto_active_connections", "inspecto_files_waiting_stability");

    /** {@code GET /metrics/acquisition} — the acquisition counters/gauges/histogram as JSON (UI dashboard). */
    private Object acquisitionMetrics() {
        return com.gamma.metrics.MetricRegistry.global().snapshot(ACQ_METRICS::contains);
    }

    private static ApiException notFound(String name) {
        return new ApiException(404, "no pipeline named '" + name + "'");
    }

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

    private static String str(Map<String, Object> body, String key) {
        return ApiContext.str(body, key);
    }

    private static String query(HttpExchange ex, String key) {
        return ApiContext.query(ex, key);
    }

    // Route registration. The core (Personal edition) is auth-free — every route is open.
    // Standard/Enterprise editions re-introduce authorization out-of-band via the security module.
    @Override public void get (String pattern, Handler h) { routes.add(new Route("GET",    Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void post(String pattern, Handler h) { routes.add(new Route("POST",   Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void put   (String pattern, Handler h) { routes.add(new Route("PUT",    Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void delete(String pattern, Handler h) { routes.add(new Route("DELETE", Pattern.compile("^" + pattern + "$"), h)); }

    private record Route(String method, Pattern pattern, Handler handler) {}
}
