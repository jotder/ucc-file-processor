package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.catalog.EdgeKind;
import com.gamma.catalog.MetadataEdge;
import com.gamma.catalog.MetadataGraph;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.ConfigValidator;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.MultiSourceProcessor;
import com.gamma.inspector.ReprocessCommand;
import com.gamma.service.SourceService;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        // ── v2.8.0: config-driven jobs (cron / event / manual) ──
        get("/jobs", (e, m) -> jobs().jobs());
        // T27 job-execution reporting (DuckDB projection; 404 unless -Djobs.backend is set). Fixed
        // sub-paths, registered before the /jobs/{name}/runs regex (single-segment, so no collision).
        get("/jobs/metrics", (e, m) -> jobRunStore().metrics(query(e, "job")));
        get("/jobs/runs", (e, m) -> jobRunStore().recentRuns(parseIntOr(query(e, "limit"), 50), query(e, "job")));
        get("/jobs/failures", (e, m) -> jobRunStore().failureTrend(parseIntOr(query(e, "days"), 30)));
        get("/jobs/([^/]+)/runs", (e, m) -> jobs().runsFor(name(m)));
        post("/jobs/([^/]+)/trigger", (e, m) -> {
            if (!jobs().trigger(name(m), query(e, "actor")))   // optional ?actor= attributes the manual fire (T32)
                throw new ApiException(404, "no job named '" + name(m) + "'");
            return Map.of("job", name(m), "status", "triggered");
        });

        // ── v2.9.0: Stage-2 enrichment run audit + lineage + rollup ──
        get("/enrichment", (e, m) -> enrichment().views());
        get("/enrichment/([^/]+)/runs", (e, m) -> enrichment().runs(enrichJob(m)));
        get("/enrichment/([^/]+)/lineage", (e, m) ->
                enrichment().lineage(enrichJob(m), query(e, "runId")));
        get("/enrichment/([^/]+)/report", (e, m) ->
                service.reports().enrichmentReport(enrichJob(m), window(e)));

        // ── v3.2.0: metadata graph / data catalog (scope assist.read; control satisfies it) ──
        get("/catalog", (e, m) -> service.catalog().tables());
        get("/catalog/kpis", (e, m) -> catalogKpis());
        get("/catalog/graph", (e, m) -> service.catalog().traverse(
                query(e, "from"),
                parseIntOr(query(e, "depth"), 1),
                direction(query(e, "direction")),
                nodeKinds(query(e, "kinds")),
                edgeKinds(query(e, "edgeKinds")),
                "true".equalsIgnoreCase(query(e, "overlay"))));
        get("/catalog/tables/(.+)", (e, m) -> catalogNodeDetail(name(m)));

        // ── v3.2.0: declarative config spec (UI form rendering + LLM-constrained authoring) ──
        get("/config/spec/(.+)", (e, m) -> {
            ConfigSpec spec = ConfigSpecs.forType(name(m));
            if (spec == null) throw new ApiException(404, "unknown config type: " + name(m));
            return spec;
        });

        // Validate a saved file ({"configPath":"…"}) OR an unsaved draft ({"type":…,"config":{…}}).
        post("/validate", (e, m) -> validate(body(e)));

        // Persist a validated config draft to disk (scope assist.write; jailed under -Dassist.write.root).
        post("/config/write", (e, m) -> writeConfig(e, body(e)));

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


        // ── data-plane provenance (T22, §11): per-(node, relationship) record counts of a past flow run,
        // for painting quantities onto the FlowGraph edges (Sankey). 404 unless -Dprovenance.backend is set. ──
        get("/provenance", (e, m) -> provenanceData(query(e, "flow"), query(e, "batch")));
        get("/provenance/batches", (e, m) -> provenanceBatches(query(e, "flow"), query(e, "limit")));

        // Feature route modules extracted from this class (see RouteModule); each owns its own routes + docs.
        for (RouteModule module : List.of(
                new ConnectionRoutes(), new ViewRoutes(), new FlowRoutes(), new ComponentRoutes(),
                new EventRoutes(), new ObjectRoutes()))
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
     * Validate a config and return structured {@link Finding}s. Two body forms:
     * <ul>
     *   <li>{@code {"configPath":"…"}} — load the file, return the pipeline name, the legacy
     *       {@code warnings} string list (back-compat), and the structured {@code findings};</li>
     *   <li>{@code {"type":"pipeline|enrichment|job|schema|meta","config":{…}}} — validate an
     *       in-memory draft against that type's spec with no file written, returning {@code findings}.
     *       Add {@code "safety":true} to also run the hard-fail {@link ConfigSafetyValidator} (path
     *       jail / numeric bounds / output allow-list, R6) and merge its findings; omitted/false
     *       leaves the response unchanged.</li>
     * </ul>
     * {@code clean} is true when there are no findings.
     */
    private Object validate(Map<String, Object> body) throws IOException {
        String configPath = str(body, "configPath");
        if (configPath != null) {
            PipelineConfig cfg = PipelineConfig.load(configPath);
            List<String> warnings = ConfigValidator.validate(cfg);
            List<Finding> findings = ConfigLoader.filesystem()
                    .validate(ConfigSpecs.pipeline(), ConfigLoader.filesystem().decode(configPath));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pipeline", cfg.identity().pipelineName());
            r.put("warnings", warnings);     // legacy string form (back-compat)
            r.put("findings", findings);     // structured form (v3.2.0)
            r.put("clean", warnings.isEmpty());
            return r;
        }
        String type = str(body, "type");
        Object cfgObj = body.get("config");
        if (type == null || !(cfgObj instanceof Map<?, ?>)) {
            throw new ApiException(400,
                    "body must include 'configPath', or 'type' + 'config' (a draft config map)");
        }
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) cfgObj;
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, draft));
        // Pre-flight: warn when a pipeline draft's schema_file won't resolve on this server —
        // registration would otherwise fail later with an opaque error (v4.1.0).
        findings.addAll(schemaFileFindings(type, draft, Severity.WARNING));
        // Opt-in hard-fail safety gate (R6): merged in only when the caller asks, so the default
        // /validate response is byte-for-byte unchanged for existing callers.
        boolean safety = "true".equalsIgnoreCase(String.valueOf(body.get("safety")));
        if (safety) {
            findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("findings", findings);
        r.put("safetyChecked", safety);
        r.put("clean", findings.isEmpty());
        return r;
    }

    /**
     * Persist a validated config draft to disk as a {@code .toon} file (v4.1.0, scope
     * {@code assist.write}). Closes the author→save loop so a suggested or hand-edited config no
     * longer has to be copied to disk out-of-band.
     *
     * <p>Body: {@code {"type":"pipeline|enrichment|job|schema|meta", "config":{…},
     * "subdir":"optional/relative", "overwrite":false}}. Gated fail-closed, in order:
     * <ol>
     *   <li>writes are disabled unless {@code -Dassist.write.root} is set → 503;</li>
     *   <li>absent/unknown type → 400/404; missing {@code config} map → 400;</li>
     *   <li>spec validation + the hard-fail {@link ConfigSafetyValidator} (R6) gate: any
     *       {@code ERROR} finding → 422 (findings returned); warnings pass through;</li>
     *   <li>the filename is derived from the config's own identity field (never a caller-supplied
     *       path) and sanitised to one safe token → 422 if blank/unsafe;</li>
     *   <li>the resolved target is jailed under the write root — an optional {@code subdir} must be
     *       relative and may not escape → 400/403;</li>
     *   <li>an existing file is refused unless {@code overwrite:true} → 409.</li>
     * </ol>
     * On success the draft is encoded via {@link ConfigCodec#toToon} and written atomically
     * (temp file + move); the response carries the root-relative path, byte count, whether an
     * existing file was replaced, and any (warning-level) findings.
     */
    private Object writeConfig(HttpExchange ex, Map<String, Object> body) throws IOException {
        if (writeRoot == null)
            throw new ApiException(503, "config write disabled: set -Dassist.write.root to enable");

        String type = str(body, "type");
        Object cfgObj = body.get("config");
        if (type == null || !(cfgObj instanceof Map<?, ?>))
            throw new ApiException(400, "body must include 'type' and 'config' (a draft config map)");
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) cfgObj;

        // Gate: spec validation + the hard-fail safety check (R6). Block on ERRORs; warnings pass.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, draft));
        findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
        // Warning only: the save still succeeds (the schema file may be created afterwards), but
        // the operator learns now that Register would fail on this host.
        findings.addAll(schemaFileFindings(type, draft, Severity.WARNING));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            respond(ex, 422, Map.of("type", type, "written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));
            return HANDLED;
        }

        // Filename from the config's own identity field — no caller-controlled path component.
        String idField = identityField(type);
        String rawName = dottedString(draft, idField);
        if (rawName == null || rawName.isBlank())
            throw new ApiException(422, "config is missing its identity field '" + idField + "'");
        String fileName = rawName.trim();
        if (fileName.contains("..") || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw new ApiException(422,
                    "unsafe config name '" + rawName + "' (allowed: letters, digits, '.', '_', '-')");

        // Resolve under the write root; an optional subdir must stay inside it (path jail).
        Path dir = writeRoot;
        String subdir = str(body, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = writeRoot.resolve(sub).normalize();
            if (!dir.startsWith(writeRoot)) throw new ApiException(403, "subdir escapes the write root");
        }
        Path target = dir.resolve(fileName + ".toon").normalize();
        if (!target.startsWith(writeRoot)) throw new ApiException(403, "resolved path escapes the write root");

        boolean exists = Files.exists(target);
        boolean overwrite = "true".equalsIgnoreCase(String.valueOf(body.get("overwrite")));
        if (exists && !overwrite)
            throw new ApiException(409, "file exists: " + writeRoot.relativize(target).toString().replace('\\', '/')
                    + " (pass overwrite:true to replace)");

        // Encode and write atomically: a partial/concurrent reader never sees a half-written file.
        byte[] bytes = ConfigCodec.toToon(draft).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".cfg-");
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        log.info("[CONFIG-WRITE] type={} wrote {} ({} bytes, overwrote={})", type, rel, bytes.length, exists);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("written", true);
        r.put("path", rel);
        r.put("name", fileName);
        r.put("bytes", bytes.length);
        r.put("overwritten", exists);
        r.put("findings", findings);   // warnings only at this point (errors would have 422'd)
        return r;
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
        findings.addAll(schemaFileFindings("pipeline", raw, Severity.ERROR));
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

    /**
     * Pre-flight check that a pipeline draft's schema reference(s) resolve on <em>this server's</em>
     * filesystem (v4.1.0). {@link PipelineConfig} resolves {@code schema_file} relative to the
     * process working directory, so a draft that validates clean can still fail at registration
     * with an opaque 422 — this surfaces it early, as a structured finding anchored to the field.
     * Checks both the legacy {@code processing.schema_file} and the multi-schema
     * {@code processing.schemas[].schema_file}. No-op for non-pipeline types.
     *
     * @param severity WARNING at validate/save time (the file may be created later, or the config
     *                 may be destined for another host); ERROR at register time (it will fail)
     */
    static List<Finding> schemaFileFindings(String type, Map<String, Object> draft, Severity severity) {
        if (!"pipeline".equals(type)) return List.of();
        Object procObj = draft.get("processing");
        if (!(procObj instanceof Map<?, ?> proc)) return List.of();
        List<Finding> out = new ArrayList<>();
        if (proc.get("schema_file") instanceof String s && !s.isBlank() && !Files.isRegularFile(Path.of(s)))
            out.add(new Finding(severity, "processing.schema_file", unresolvable(s)));
        if (proc.get("schemas") instanceof List<?> defs) {
            for (int i = 0; i < defs.size(); i++) {
                if (defs.get(i) instanceof Map<?, ?> def
                        && def.get("schema_file") instanceof String s && !s.isBlank()
                        && !Files.isRegularFile(Path.of(s)))
                    out.add(new Finding(severity, "processing.schemas[" + i + "].schema_file",
                            unresolvable(s)));
            }
        }
        return out;
    }

    private static String unresolvable(String schemaPath) {
        return "schema file does not resolve on the server: '" + schemaPath
                + "' (relative paths resolve against the server's working directory: "
                + Path.of("").toAbsolutePath() + ")";
    }

    /** Dotted path into the config map that holds a config's stable identity (its filename source). */
    private static String identityField(String type) {
        return switch (type) {
            case "job"    -> "job.name";
            case "schema" -> "raw.name";
            default       -> "name";   // pipeline, enrichment, meta
        };
    }

    /** Read a dotted key (e.g. {@code job.name}) from a nested config map, or {@code null} if absent. */
    private static String dottedString(Map<String, Object> map, String dotted) {
        Object cur = map;
        for (String seg : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(seg);
        }
        return cur == null ? null : String.valueOf(cur);
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
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
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

    /**
     * {@code GET /provenance?flow=&batch=} — the per-(node, relationship) record counts of one flow run (T22).
     * A consumer paints each {@code (nodeId, rel)} onto its outgoing {@code FlowGraph} edge as the Sankey weight.
     * 400 if either param is missing, 404 when no provenance backend is configured.
     */
    private Object provenanceData(String flow, String batch) {
        if (flow == null || flow.isBlank() || batch == null || batch.isBlank())
            throw new ApiException(400, "both 'flow' and 'batch' query params are required");
        return provenanceStore().query(flow, batch);
    }

    /** {@code GET /provenance/batches?flow=&limit=} — recent runs of a flow (newest first) to pick one to inspect. */
    private Object provenanceBatches(String flow, String limit) {
        if (flow == null || flow.isBlank())
            throw new ApiException(400, "the 'flow' query param is required");
        return provenanceStore().batches(flow, parseIntOr(limit, 20));
    }

    /** The DuckDB data-plane provenance store (T21/T22), or a 404 when no backend is configured (-Dprovenance.backend). */
    private com.gamma.flow.exec.DbProvenanceStore provenanceStore() {
        return jobs().provenanceStore().orElseThrow(() -> new ApiException(404,
                "provenance DB not enabled (set -Dprovenance.backend=duckdb)"));
    }

    /** The job registry, or a 404 when no jobs are registered on this service. */
    private com.gamma.job.JobService jobs() {
        return service.jobService().orElseThrow(() -> new ApiException(404, "no jobs registered"));
    }

    /** The DuckDB job-run reporting store (T27), or a 404 when no backend is configured (-Djobs.backend). */
    private com.gamma.job.DbJobRunStore jobRunStore() {
        return jobs().runStore().orElseThrow(() -> new ApiException(404,
                "job reporting DB not enabled (set -Djobs.backend=duckdb)"));
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
        return ApiContext.parseIntOr(s, def);
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
