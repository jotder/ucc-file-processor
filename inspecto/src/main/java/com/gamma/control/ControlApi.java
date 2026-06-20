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
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventQuery;
import com.gamma.event.SavedView;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.ObjectNote;
import com.gamma.flow.FlowProjection;
import com.gamma.flow.PipelineLift;
import com.gamma.ops.rca.RcaTemplate;
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
    /** Returned by a handler that has already written its own (non-JSON) response. */
    private static final Object HANDLED = new Object();

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

        // ── v4.2.0 (Phase 1): Operational Event Viewer — the append-only "what happened" feed, backed
        // by EventLog/EventStore (in-memory ring or rolling Parquet). CONTROL-scoped reads. Specific
        // paths are registered before the /events/{id} catch so first-match-wins resolves them. ──
        get("/events", (e, m) -> toMaps(service.events().recent(parseIntOr(query(e, "limit"), 50))));
        get("/events/search", (e, m) -> toMaps(service.events().query(eventQuery(e, EventQuery.DEFAULT_LIMIT))));
        get("/events/export", (e, m) -> exportEvents(e));
        get("/events/views", (e, m) -> service.savedViews().list());
        post("/events/views", (e, m) -> saveView(body(e)));
        post("/events/views/([^/]+)/delete", (e, m) -> {
            if (!service.savedViews().delete(name(m)))
                throw new ApiException(404, "no saved view named '" + name(m) + "'");
            return Map.of("name", name(m), "deleted", true);
        });
        get("/events/([^/]+)", (e, m) -> eventById(name(m)));

        // ── v4.3.0 (Phase 2): Alert Center — mutable operational objects (ALERT now; ISSUE/CASE later)
        // with a workflow-checked lifecycle. CONTROL-scoped. Specific verbs are registered before the
        // /objects/{id} catch so first-match-wins resolves them. v4.4.0 (Phase 3) adds POST /objects so
        // operators can create ISSUEs (ALERTs are auto-promoted); lifecycle moves use /transition. ──
        get("/objects", (e, m) -> toObjectMaps(service.objects().query(objectQuery(e))));
        post("/objects", (e, m) -> createObject(body(e)));
        post("/objects/([^/]+)/ack", (e, m) -> transition(name(m), "ack", null, body(e)));
        post("/objects/([^/]+)/resolve", (e, m) -> transition(name(m), "resolve", null, body(e)));
        post("/objects/([^/]+)/transition", (e, m) -> transitionFromBody(name(m), body(e)));
        post("/objects/([^/]+)/links", (e, m) -> createLink(name(m), body(e)));
        get("/objects/([^/]+)/links", (e, m) -> toLinkMaps(service.objects().linksOf(name(m))));
        get("/objects/([^/]+)/graph", (e, m) -> objectGraph(name(m), e));
        post("/objects/([^/]+)/comments", (e, m) -> addComment(name(m), body(e)));
        get("/objects/([^/]+)/comments", (e, m) -> toNoteMaps(service.objects().notesOf(name(m), NoteKind.COMMENT)));
        post("/objects/([^/]+)/attachments", (e, m) -> addAttachment(name(m), body(e)));
        get("/objects/([^/]+)/attachments", (e, m) -> toNoteMaps(service.objects().notesOf(name(m), NoteKind.ATTACHMENT)));
        post("/objects/([^/]+)/rca", (e, m) -> applyRca(name(m), body(e)));
        get("/objects/([^/]+)", (e, m) -> objectById(name(m)));
        get("/rca/templates", (e, m) -> rcaTemplateList());

        // ── Acquisition / Sources UI: a flat view of every pipeline's source acquisition config +
        // a JSON acquisition-metrics snapshot (the Prometheus /metrics is text-only). CONTROL-scoped. ──
        get("/sources", (e, m) -> service.sources());
        get("/metrics/acquisition", (e, m) -> acquisitionMetrics());

        // ── Flow graph (read-only): the pipeline-as-graph projection for the G6 visualiser + editor
        // palette (doc §6, T31). Every registered *_pipeline.toon is lifted to a FlowGraph on demand
        // (PipelineLift, lossless) and projected structurally. The fixed /node-types and the bare
        // /flows collection are anchored, so they never collide with /flows/{id}/graph. ──
        get("/flows", (e, m) -> flowSummaries());
        get("/flows/node-types", (e, m) -> FlowProjection.catalog());
        get("/flows/combined", (e, m) -> combinedFlows());     // T24: pipeline+job topology joined at the shared store
        // ── Authored-flow topology CRUD (T19, §7.1): build/validate/persist *_flow.toon under
        // <write-root>/flows. A distinct namespace from the read-only lifted-pipeline projection above; all
        // anchored, so /flows/authored* never collides with /flows/{id}/graph. ──
        get("/flows/authored", (e, m) -> authoredFlowList());
        post("/flows/authored", (e, m) -> createFlow(body(e)));
        get("/flows/authored/([^/]+)", (e, m) -> authoredFlow(name(m)));
        get("/flows/authored/([^/]+)/raw", (e, m) -> authoredFlowRaw(name(m)));   // lossless map for the editor
        put("/flows/authored/([^/]+)", (e, m) -> updateFlow(name(m), body(e)));
        delete("/flows/authored/([^/]+)", (e, m) -> deleteFlow(name(m)));
        post("/flows/authored/([^/]+)/nodes", (e, m) -> addFlowNode(name(m), body(e)));
        post("/flows/authored/([^/]+)/edges", (e, m) -> addFlowEdge(name(m), body(e)));
        post("/flows/authored/([^/]+)/dry-run", (e, m) -> dryRunFlow(name(m), body(e)));   // T18: bounded sample
        get("/flows/([^/]+)/graph", (e, m) -> FlowProjection.graph(PipelineLift.lift(cfg(m))));

        // ── data-plane provenance (T22, §11): per-(node, relationship) record counts of a past flow run,
        // for painting quantities onto the FlowGraph edges (Sankey). 404 unless -Dprovenance.backend is set. ──
        get("/provenance", (e, m) -> provenanceData(query(e, "flow"), query(e, "batch")));
        get("/provenance/batches", (e, m) -> provenanceBatches(query(e, "flow"), query(e, "limit")));

        // Feature route modules extracted from this class (see RouteModule); each owns its own routes + docs.
        for (RouteModule module : List.of(new ConnectionRoutes(), new ViewRoutes())) module.register(this);

        // ── Component registry CRUD (T19, §7.1): grammar/schema/transform/sink under <write-root>/registry,
        // generalising the connection write pattern (write-root gated, jailed, atomic, safe-delete). The
        // two-segment /{type}/{id} routes are distinct from the one-segment list (patterns are anchored). ──
        get("/components/([^/]+)", (e, m) -> componentList(name(m)));
        get("/components/([^/]+)/([^/]+)", (e, m) -> componentById(name(m), param(m, 2)));
        post("/components/([^/]+)", (e, m) -> createComponent(name(m), body(e)));
        put("/components/([^/]+)/([^/]+)", (e, m) -> updateComponent(name(m), param(m, 2), body(e)));
        delete("/components/([^/]+)/([^/]+)", (e, m) -> deleteComponent(name(m), param(m, 2)));
        // T18 dry-run/test: preview a component over a sample through the production logic (scratch-only).
        post("/components/transform/([^/]+)/test", (e, m) -> previewTransform(name(m), body(e)));
        post("/components/grammar/([^/]+)/test", (e, m) -> previewGrammar(name(m), body(e)));
        post("/components/schema/([^/]+)/test", (e, m) -> previewSchema(name(m), body(e)));
        post("/components/sink/([^/]+)/test", (e, m) -> previewSink(name(m), body(e)));

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
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        return HANDLED;
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

    /** Lift every registered pipeline to a {@link com.gamma.flow.FlowGraph} and project a compact summary (GET /flows). */
    private List<Map<String, Object>> flowSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourceService.PipelineView pv : service.pipelines()) {
            service.configFor(pv.name())
                    .ifPresent(c -> out.add(FlowProjection.summary(PipelineLift.lift(c))));
        }
        return out;
    }

    /** Lift every registered pipeline and project the combined pipeline+job topology (GET /flows/combined, T24). */
    private Map<String, Object> combinedFlows() {
        return FlowProjection.combined(liftedFlows());
    }

    /** Every registered pipeline lifted to a {@link com.gamma.flow.FlowGraph} (the available flows). */
    private List<com.gamma.flow.FlowGraph> liftedFlows() {
        List<com.gamma.flow.FlowGraph> graphs = new ArrayList<>();
        for (SourceService.PipelineView pv : service.pipelines()) {
            service.configFor(pv.name()).ifPresent(c -> graphs.add(PipelineLift.lift(c)));
        }
        return graphs;
    }

    // ── Component registry CRUD (T19, §7.1): generalise the connection write pattern to the non-secret
    // component types (grammar/schema/transform/sink) under <write-root>/registry/<typeDir>/<id>.toon.
    // connection keeps its own secret-masking CRUD; safe-delete refuses a component a flow still uses. ──

    /** The registry root under the write root, or {@code null} when writes are disabled (no write root). */
    private Path componentRootOrNull() {
        return writeRoot == null ? null : writeRoot.resolve("registry");
    }

    private com.gamma.flow.ComponentStore componentStore() {
        requireWriteRoot();
        return new com.gamma.flow.ComponentStore(writeRoot.resolve("registry"));
    }

    /** The JSON shape for one component: identity + parsed content. */
    private static Map<String, Object> componentDoc(com.gamma.flow.ComponentRegistry.Component c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", c.type());
        m.put("name", c.name());
        m.put("ref", c.ref());
        m.put("content", c.content());
        return m;
    }

    /** {@code GET /components/{type}} — list components of a type (empty when no registry/write root). */
    private Object componentList(String type) {
        Path root = componentRootOrNull();
        if (root == null) return List.of();
        try {
            return new com.gamma.flow.ComponentStore(root).list(type).stream().map(ControlApi::componentDoc).toList();
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** {@code GET /components/{type}/{id}} — one component; 404 if absent. */
    private Object componentById(String type, String id) {
        Path root = componentRootOrNull();
        com.gamma.flow.ComponentRegistry.Component c;
        try {
            c = root == null ? null : new com.gamma.flow.ComponentStore(root).get(type, id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        return componentDoc(c);
    }

    /** {@code POST /components/{type}} — create a component (id from body {@code id}/{@code name}); 409 if it exists. */
    private Object createComponent(String type, Map<String, Object> body) throws IOException {
        com.gamma.flow.ComponentStore store = componentStore();
        String id = str(body, "id");
        if (id == null || id.isBlank()) id = str(body, "name");
        if (id == null || id.isBlank()) throw new ApiException(400, "body must include 'id' (or 'name')");
        if (componentExists(store, type, id))
            throw new ApiException(409, type + " component '" + id + "' already exists (use PUT to update)");
        return writeComponent(store, type, id, body);
    }

    /** {@code PUT /components/{type}/{id}} — create or replace a component; 404 if absent. */
    private Object updateComponent(String type, String id, Map<String, Object> body) throws IOException {
        com.gamma.flow.ComponentStore store = componentStore();
        if (!componentExists(store, type, id)) throw new ApiException(404, "no " + type + " component '" + id + "'");
        return writeComponent(store, type, id, body);
    }

    /** {@code DELETE /components/{type}/{id}} — safe-delete; 404 if absent, 409 if a flow references it. */
    private Object deleteComponent(String type, String id) throws IOException {
        com.gamma.flow.ComponentStore store = componentStore();
        if (!componentExists(store, type, id)) throw new ApiException(404, "no " + type + " component '" + id + "'");
        List<String> refs = com.gamma.flow.FlowReferences.referencedBy(type + "/" + id, liftedFlows());
        if (!refs.isEmpty())
            throw new ApiException(409, type + " component '" + id + "' is referenced by flow(s): "
                    + String.join(", ", refs));
        boolean removed;
        try {
            removed = store.delete(type, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return Map.of("type", type, "id", id, "deleted", true, "fileRemoved", removed);
    }

    // ── Authored-flow CRUD (T19, §7.1): persist/validate *_flow.toon under <write-root>/flows ──

    private Path flowsRootOrNull() {
        return writeRoot == null ? null : writeRoot.resolve("flows");
    }

    private com.gamma.flow.FlowStore flowStore() {
        requireWriteRoot();
        return new com.gamma.flow.FlowStore(writeRoot.resolve("flows"));
    }

    /** {@code GET /flows/authored} — summaries of every authored flow (empty when no write root). */
    private Object authoredFlowList() {
        Path root = flowsRootOrNull();
        if (root == null) return List.of();
        return new com.gamma.flow.FlowStore(root).list().stream().map(FlowProjection::summary).toList();
    }

    /** {@code GET /flows/authored/{id}} — one authored flow's graph projection; 404 if absent. */
    private Object authoredFlow(String id) {
        Path root = flowsRootOrNull();
        com.gamma.flow.FlowGraph g = root == null ? null : new com.gamma.flow.FlowStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        return FlowProjection.graph(g);
    }

    /**
     * {@code GET /flows/authored/{id}/raw} — the <b>lossless</b> authored definition ({@link com.gamma.flow.FlowCodec#toMap},
     * nodes with their config) so the editor can round-trip a flow without dropping node config; the
     * {@link #authoredFlow} projection is structural-only. 404 if absent.
     */
    private Object authoredFlowRaw(String id) {
        Path root = flowsRootOrNull();
        com.gamma.flow.FlowGraph g = root == null ? null : new com.gamma.flow.FlowStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        return com.gamma.flow.FlowCodec.toMap(g);
    }

    /** {@code POST /flows/authored} — create an authored flow from a posted flow definition; 409 if it exists. */
    private Object createFlow(Map<String, Object> body) throws IOException {
        com.gamma.flow.FlowStore store = flowStore();
        com.gamma.flow.FlowGraph g = parseAndValidateFlow(body);
        String id = g.name();
        if (flowExists(store, id))
            throw new ApiException(409, "authored flow '" + id + "' already exists (use PUT to update)");
        return writeFlow(store, id, g);
    }

    /** {@code PUT /flows/authored/{id}} — create or replace an authored flow (URL id is authoritative). */
    private Object updateFlow(String id, Map<String, Object> body) throws IOException {
        com.gamma.flow.FlowStore store = flowStore();
        Map<String, Object> withId = new LinkedHashMap<>(body);
        withId.put("name", id);   // the URL id wins over any name in the body
        return writeFlow(store, id, parseAndValidateFlow(withId));
    }

    /** {@code DELETE /flows/authored/{id}} — remove an authored flow; 404 if absent. */
    private Object deleteFlow(String id) throws IOException {
        com.gamma.flow.FlowStore store = flowStore();
        if (!flowExists(store, id)) throw new ApiException(404, "no authored flow '" + id + "'");
        boolean removed;
        try {
            removed = store.delete(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return Map.of("id", id, "deleted", true, "fileRemoved", removed);
    }

    /** {@code POST /flows/authored/{id}/nodes} — add (or replace by id) a node, re-validate, persist. */
    private Object addFlowNode(String id, Map<String, Object> body) throws IOException {
        com.gamma.flow.FlowStore store = flowStore();
        com.gamma.flow.FlowGraph g = requireAuthoredFlow(store, id);
        com.gamma.flow.FlowNode node;
        try {
            node = com.gamma.flow.FlowCodec.nodeFromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
        List<com.gamma.flow.FlowNode> nodes = new ArrayList<>(g.nodes());
        nodes.removeIf(n -> n.id().equals(node.id()));   // upsert by node id
        nodes.add(node);
        com.gamma.flow.FlowGraph updated = new com.gamma.flow.FlowGraph(g.name(), g.active(), nodes, g.edges());
        validateFlow(updated);
        return writeFlow(store, id, updated);
    }

    /** {@code POST /flows/authored/{id}/edges} — add an edge, re-validate, persist. */
    private Object addFlowEdge(String id, Map<String, Object> body) throws IOException {
        com.gamma.flow.FlowStore store = flowStore();
        com.gamma.flow.FlowGraph g = requireAuthoredFlow(store, id);
        com.gamma.flow.FlowEdge edge;
        try {
            edge = com.gamma.flow.FlowCodec.edgeFromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
        List<com.gamma.flow.FlowEdge> edges = new ArrayList<>(g.edges());
        edges.add(edge);
        com.gamma.flow.FlowGraph updated = new com.gamma.flow.FlowGraph(g.name(), g.active(), g.nodes(), edges);
        validateFlow(updated);
        return writeFlow(store, id, updated);
    }

    private com.gamma.flow.FlowGraph requireAuthoredFlow(com.gamma.flow.FlowStore store, String id) {
        try {
            return store.get(id).orElseThrow(() -> new ApiException(404, "no authored flow '" + id + "'"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** Parse a flow definition (400 on a malformed shape) and validate it (422 on validation errors). */
    private com.gamma.flow.FlowGraph parseAndValidateFlow(Map<String, Object> body) {
        com.gamma.flow.FlowGraph g;
        try {
            g = com.gamma.flow.FlowCodec.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        validateFlow(g);
        return g;
    }

    private void validateFlow(com.gamma.flow.FlowGraph g) {
        com.gamma.flow.FlowValidator.Result r = com.gamma.flow.FlowValidator.validate(g);
        if (!r.ok())
            throw new ApiException(422, "flow validation failed: " + r.errors().stream()
                    .map(i -> i.code() + " — " + i.message()).toList());
    }

    private static boolean flowExists(com.gamma.flow.FlowStore store, String id) {
        try {
            return store.exists(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private Object writeFlow(com.gamma.flow.FlowStore store, String id, com.gamma.flow.FlowGraph g) throws IOException {
        try {
            store.write(id, g);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
        log.info("[FLOW-WRITE] wrote authored flow {}", id);
        return FlowProjection.graph(g);
    }

    /**
     * {@code POST /components/transform/{id}/test} — dry-run a transform component over {@code sampleRows}
     * through the production {@link com.gamma.flow.exec.RowShaper} on a throwaway DuckDB (T18, §7.2). 404 if
     * the component is absent, 422 if it is not a {@code transform.*} type, 400 on a bad sample / unsupported
     * operator. Never touches production output.
     */
    @SuppressWarnings("unchecked")
    private Object previewTransform(String id, Map<String, Object> body) {
        Path root = componentRootOrNull();
        com.gamma.flow.ComponentRegistry.Component c;
        try {
            c = root == null ? null : new com.gamma.flow.ComponentStore(root).get("transform", id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no transform component '" + id + "'");
        String type = str(c.content(), "type");
        if (type == null || !type.startsWith("transform."))
            throw new ApiException(422, "component '" + id + "' is not a transform ('type: transform.*' required)");

        com.gamma.flow.FlowNode node = new com.gamma.flow.FlowNode(id, type, c.content(), null);
        try {
            return com.gamma.flow.exec.ComponentPreview.transform(node, sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (java.sql.SQLException | IOException e) {
            throw new ApiException(422, "preview failed: " + e.getMessage());
        }
    }

    /**
     * {@code POST /components/grammar/{id}/test} — parse raw {@code sampleText} with a grammar component's CSV
     * dialect through the production {@code read_csv} on a throwaway DuckDB (T18, §7.2). 404 if absent, 400 on
     * empty input, 422 on a parse error. Never touches production output.
     */
    private Object previewGrammar(String id, Map<String, Object> body) {
        com.gamma.flow.ComponentRegistry.Component c = requireComponent("grammar", id);
        try {
            return com.gamma.flow.exec.ComponentPreview.grammar(c.content(), sampleText(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (java.sql.SQLException | IOException e) {
            throw new ApiException(422, "preview failed: " + e.getMessage());
        }
    }

    /**
     * {@code POST /components/schema/{id}/test} — {@code TRY_CAST} {@code sampleRows} against a schema
     * component's typed fields, splitting {@code data} / {@code rejected}, on a throwaway DuckDB (T18, §7.2).
     * 404 if absent, 400 on a bad sample, 422 on a cast/SQL error. Never touches production output.
     */
    private Object previewSchema(String id, Map<String, Object> body) {
        com.gamma.flow.ComponentRegistry.Component c = requireComponent("schema", id);
        try {
            return com.gamma.flow.exec.ComponentPreview.schema(c.content(), sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (java.sql.SQLException | IOException e) {
            throw new ApiException(422, "preview failed: " + e.getMessage());
        }
    }

    /**
     * {@code POST /components/sink/{id}/test} — scratch-validate a sink component against {@code sampleRows}
     * (store/format/partition checks; row count + bounded sample, no write) (T18, §7.2). 404 if absent, 400 on
     * a bad sample.
     */
    private Object previewSink(String id, Map<String, Object> body) {
        com.gamma.flow.ComponentRegistry.Component c = requireComponent("sink", id);
        try {
            return com.gamma.flow.exec.ComponentPreview.sink(c.content(), sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** Load a component by {@code type}/{@code id} or fail with the standard 400/404 (shared by the preview handlers). */
    private com.gamma.flow.ComponentRegistry.Component requireComponent(String type, String id) {
        Path root = componentRootOrNull();
        com.gamma.flow.ComponentRegistry.Component c;
        try {
            c = root == null ? null : new com.gamma.flow.ComponentStore(root).get(type, id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        return c;
    }

    /**
     * {@code POST /flows/authored/{id}/dry-run} — run a bounded sample through an authored flow's
     * transform→sink subgraph on a throwaway DuckDB (T18, §7.2); per-node + per-sink row counts. 404 if the
     * flow is absent, 400 on a bad sample, 422 on a validation/SQL error. Never touches production output.
     */
    private Object dryRunFlow(String id, Map<String, Object> body) {
        Path root = flowsRootOrNull();
        com.gamma.flow.FlowGraph g;
        try {
            g = root == null ? null : new com.gamma.flow.FlowStore(root).get(id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        try {
            return com.gamma.flow.exec.FlowDryRun.run(g, sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (Exception e) {
            throw new ApiException(422, "dry-run failed: " + e.getMessage());
        }
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

    /** Extract raw {@code sampleText} from a request body (the text a grammar would parse); empty if absent. */
    private static String sampleText(Map<String, Object> body) {
        Object t = body.get("sampleText");
        return t == null ? "" : t.toString();
    }

    /** Extract the {@code sampleRows} array from a request body (each element a row map); empty if absent. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sampleRows(Map<String, Object> body) {
        List<Map<String, Object>> sample = new ArrayList<>();
        if (body.get("sampleRows") instanceof List<?> rows) {
            for (Object o : rows) if (o instanceof Map<?, ?> r) sample.add((Map<String, Object>) r);
        }
        return sample;
    }

    private static boolean componentExists(com.gamma.flow.ComponentStore store, String type, String id) {
        try {
            return store.exists(type, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** Write a component: the body is the content (the routing-only {@code id} key is stripped); 422 on bad input. */
    private Object writeComponent(com.gamma.flow.ComponentStore store, String type, String id,
                                  Map<String, Object> body) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>(body);
        content.remove("id");   // routing key, not content (the store stamps name=id)
        try {
            com.gamma.flow.ComponentRegistry.Component c = store.write(type, id, content);
            log.info("[COMPONENT-WRITE] wrote {}", c.ref());
            return componentDoc(c);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
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
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLongOr(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Long.parseLong(s.trim());
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

    // ── event viewer helpers (v4.2.0, Phase 1) ───────────────────────────────────

    private static List<Map<String, Object>> toMaps(List<Event> events) {
        return events.stream().map(Event::toMap).toList();
    }

    /** Build an {@link EventQuery} from {@code ?level=&type=&pipeline=&correlationId=&q=&from=&to=&limit=&offset=}. */
    private static EventQuery eventQuery(HttpExchange ex, int defaultLimit) {
        String level = query(ex, "level");
        return EventQuery.builder()
                .minLevel(level == null ? null : EventLevel.parse(level))
                .type(query(ex, "type"))
                .pipeline(query(ex, "pipeline"))
                .correlationId(query(ex, "correlationId"))
                .textContains(query(ex, "q"))
                .from(epochMillis(query(ex, "from")))
                .to(epochMillis(query(ex, "to")))
                .limit(parseIntOr(query(ex, "limit"), defaultLimit))
                .offset(parseIntOr(query(ex, "offset"), 0))
                .build();
    }

    /** Parse a time bound as epoch millis (all-digits) or a {@code yyyy-MM-dd[ HH:mm:ss]} string; null when blank. */
    private static Long epochMillis(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            try { return Long.parseLong(t); } catch (NumberFormatException ignore) { return null; }
        }
        try {
            String norm = (t.length() <= 10 ? t + " 00:00:00" : t.replace('T', ' ')).substring(0, 19);
            return java.time.LocalDateTime.parse(norm,
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            throw new ApiException(400, "invalid time '" + s + "' (use epoch millis or yyyy-MM-dd[ HH:mm:ss])");
        }
    }

    /** {@code GET /events/{id}} — scan the newest events (buffer + Parquet) for an exact id, else 404. */
    private Object eventById(String id) {
        return service.events().query(EventQuery.recent(EventQuery.MAX_LIMIT)).stream()
                .filter(ev -> id.equals(ev.eventId())).findFirst()
                .map(Event::toMap)
                .orElseThrow(() -> new ApiException(404, "no event with id '" + id + "'"));
    }

    /** {@code GET /events/export} — {@code ?format=csv} streams CSV; otherwise returns the JSON list. */
    private Object exportEvents(HttpExchange ex) throws IOException {
        List<Event> rows = service.events().query(eventQuery(ex, EventQuery.MAX_LIMIT));
        if ("csv".equalsIgnoreCase(query(ex, "format"))) {
            return respondText(ex, eventsCsv(rows), "text/csv; charset=utf-8");
        }
        return toMaps(rows);
    }

    private static String eventsCsv(List<Event> rows) {
        StringBuilder sb = new StringBuilder("timestamp,level,type,source,pipeline,correlationId,message\n");
        for (Event e : rows) {
            sb.append(csv(e.timestamp())).append(',').append(csv(e.level().name())).append(',')
              .append(csv(e.type())).append(',').append(csv(e.source())).append(',')
              .append(csv(e.pipeline())).append(',').append(csv(e.correlationId())).append(',')
              .append(csv(e.message())).append('\n');
        }
        return sb.toString();
    }

    /** Minimal RFC-4180 CSV field escape. */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
            return '"' + v.replace("\"", "\"\"") + '"';
        return v;
    }

    /** {@code POST /events/views} — upsert a saved view from {@code {name, level?, type?, pipeline?, correlationId?, q?, from?, to?}}. */
    private Object saveView(Map<String, Object> reqBody) {
        String viewName = str(reqBody, "name");
        if (viewName == null) throw new ApiException(400, "body must include 'name'");
        Map<String, String> filters = new LinkedHashMap<>();
        for (String k : List.of("level", "type", "pipeline", "correlationId", "q", "from", "to")) {
            String v = str(reqBody, k);
            if (v != null) filters.put(k, v);
        }
        return service.savedViews().save(new SavedView(viewName, filters, System.currentTimeMillis())).toMap();
    }

    // ── v4.3.0 (Phase 2): operational-object helpers ──────────────────────────────

    private static List<Map<String, Object>> toObjectMaps(List<OperationalObject> objs) {
        return objs.stream().map(OperationalObject::toMap).toList();
    }

    /** Build an {@link ObjectQuery} from {@code ?type=&status=&severity=&assignee=&owner=&correlationId=&q=&limit=&offset=}. */
    private static ObjectQuery objectQuery(HttpExchange ex) {
        return ObjectQuery.builder()
                .objectType(parseObjectType(query(ex, "type")))
                .status(query(ex, "status"))
                .severity(query(ex, "severity"))
                .assignee(query(ex, "assignee"))
                .owner(query(ex, "owner"))
                .correlationId(query(ex, "correlationId"))
                .textContains(query(ex, "q"))
                .limit(parseIntOr(query(ex, "limit"), ObjectQuery.DEFAULT_LIMIT))
                .offset(parseIntOr(query(ex, "offset"), 0))
                .build();
    }

    /** Parse a {@code ?type=} filter; an unknown value is a 400 rather than a silent match-everything. */
    private static ObjectType parseObjectType(String s) {
        try {
            return ObjectType.of(s);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** {@code GET /objects/{id}} — the object, or 404. */
    private Object objectById(String id) {
        return service.objects().get(id).map(OperationalObject::toMap)
                .orElseThrow(() -> new ApiException(404, "no object with id '" + id + "'"));
    }

    /**
     * {@code POST /objects} (Phase 3) — create a managed object. The complement of alert auto-promotion:
     * ALERTs are opened by the {@code AlertService}, whereas ISSUEs are operator-created here. Body
     * {@code {type?,title,description?,severity?,priority?,owner?,assignee?,correlationId?,attributes?,
     * dueAt?|dueInMinutes?}} — {@code type} defaults to {@code ISSUE}, {@code title} is required, and
     * {@code dueAt} (epoch millis) or {@code dueInMinutes} sets the SLA deadline the sweep tracks. The
     * object opens in its workflow's initial state; lifecycle moves go through {@code /objects/{id}/transition}.
     */
    private Object createObject(Map<String, Object> body) {
        String title = str(body, "title");
        if (title == null) throw new ApiException(400, "body must include 'title'");
        ObjectType type;
        try {
            type = ObjectType.of(str(body, "type"));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, ex.getMessage());
        }
        if (type == null) type = ObjectType.ISSUE;   // the create path exists for operator-created issues

        Map<String, String> attrs = new LinkedHashMap<>();
        if (body.get("attributes") instanceof Map<?, ?> bag)
            bag.forEach((k, v) -> { if (k != null && v != null) attrs.put(k.toString(), v.toString()); });
        Long dueAt = parseDueAt(body);
        if (dueAt != null) attrs.put(ObjectService.ATTR_DUE_AT, Long.toString(dueAt));

        return service.objects().open(type, title, str(body, "description"), str(body, "severity"),
                str(body, "priority"), str(body, "owner"), str(body, "assignee"),
                str(body, "correlationId"), attrs).toMap();
    }

    /** SLA deadline from the create body: absolute {@code dueAt} (epoch millis) or relative {@code dueInMinutes}. */
    private static Long parseDueAt(Map<String, Object> body) {
        Object due = body.get("dueAt");
        if (due != null) {
            long ms = parseLongOr(due.toString(), -1L);
            if (ms > 0) return ms;
        }
        Object mins = body.get("dueInMinutes");
        if (mins != null) {
            long m = parseLongOr(mins.toString(), -1L);
            if (m >= 0) return System.currentTimeMillis() + m * 60_000L;
        }
        return null;
    }

    /**
     * {@code POST /objects/{id}/links} (Phase 4) — correlate this object with another: body
     * {@code {to, relationship?, actor?}} (e.g. a CASE {@code CONTAINS} an ISSUE). A missing {@code to}
     * → 400; an unknown {@code id} or {@code to} → 404. Idempotent (a duplicate edge returns the existing one).
     */
    private Object createLink(String fromId, Map<String, Object> body) {
        String to = str(body, "to");
        if (to == null) throw new ApiException(400, "body must include 'to'");
        try {
            return service.objects().link(fromId, to, str(body, "relationship"), str(body, "actor")).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    private static List<Map<String, Object>> toLinkMaps(List<ObjectLink> links) {
        return links.stream().map(ObjectLink::toMap).toList();
    }

    /** {@code GET /objects/{id}/graph?depth=} (Phase 4) — correlation subgraph (default depth 2, capped at 5). */
    private Object objectGraph(String id, HttpExchange ex) {
        int depth = Math.min(5, Math.max(1, parseIntOr(query(ex, "depth"), 2)));
        try {
            return service.objects().graph(id, depth);
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /** {@code POST /objects/{id}/comments} (Phase 4) — add a comment; body {@code {body, author?}}. */
    private Object addComment(String id, Map<String, Object> body) {
        String text = str(body, "body");
        if (text == null) throw new ApiException(400, "body must include 'body'");
        try {
            return service.objects().comment(id, str(body, "author"), text).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /**
     * {@code POST /objects/{id}/attachments} (Phase 4) — attach an evidence reference (metadata only);
     * body {@code {name, uri, contentType?, author?, caption?}}.
     */
    private Object addAttachment(String id, Map<String, Object> body) {
        String name = str(body, "name");
        String uri = str(body, "uri");
        if (name == null || uri == null) throw new ApiException(400, "body must include 'name' and 'uri'");
        try {
            return service.objects().attach(id, str(body, "author"), name, str(body, "contentType"),
                    uri, str(body, "caption")).toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    private static List<Map<String, Object>> toNoteMaps(List<ObjectNote> notes) {
        return notes.stream().map(ObjectNote::toMap).toList();
    }

    /**
     * {@code POST /objects/{id}/rca} (Phase 4) — seed an RCA skeleton (one comment per section). Body is
     * the template: {@code {template:{name,sections[]}}} or an inline {@code {name?,sections[],actor?}}.
     */
    private Object applyRca(String id, Map<String, Object> body) {
        RcaTemplate template;
        Object t = body.get("template");
        if (t instanceof String named) {       // a *_rca.toon template referenced by name
            template = service.rcaTemplate(named).orElseThrow(
                    () -> new ApiException(404, "no RCA template named '" + named + "'"));
        } else {                                // an inline template ({template:{…}} or the body itself)
            Map<String, Object> tmpl = new LinkedHashMap<>();
            if (t instanceof Map<?, ?> tm) tm.forEach((k, v) -> tmpl.put(String.valueOf(k), v));
            else tmpl.putAll(body);
            tmpl.putIfAbsent("name", "ad-hoc"); // an inline template needn't name itself
            try {
                template = RcaTemplate.fromMap(tmpl);
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, ex.getMessage());
            }
        }
        try {
            return toNoteMaps(service.objects().applyRca(id, template, str(body, "actor")));
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
    }

    /** {@code GET /rca/templates} (Phase 4) — the RCA templates loaded from {@code *_rca.toon}, by name. */
    private Object rcaTemplateList() {
        return service.rcaTemplates().values().stream().map(RcaTemplate::toMap).toList();
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

    private void requireWriteRoot() {
        if (writeRoot == null)
            throw new ApiException(503, "connection write disabled: set -Dassist.write.root to enable");
    }

    /** {@code POST /objects/{id}/ack|resolve} — a fixed-action transition; {@code actor} from the body. */
    private Object transition(String id, String action, String target, Map<String, Object> body) {
        return doTransition(id, action, target, str(body, "actor"));
    }

    /** {@code POST /objects/{id}/transition} — body {@code {action}} or {@code {status|to}} (+ optional {@code actor}). */
    private Object transitionFromBody(String id, Map<String, Object> body) {
        String action = str(body, "action");
        String target = str(body, "status");
        if (target == null) target = str(body, "to");
        if (action == null && target == null)
            throw new ApiException(400, "body must include 'action' or 'status'");
        return doTransition(id, action, target, str(body, "actor"));
    }

    /** Apply a lifecycle transition, mapping the service's exceptions to 404 (unknown id) / 422 (illegal move). */
    private Object doTransition(String id, String action, String target, String actor) {
        try {
            OperationalObject updated = (action != null)
                    ? service.objects().transition(id, action, actor)
                    : service.objects().transitionTo(id, target, actor);
            return updated.toMap();
        } catch (java.util.NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException | IllegalArgumentException illegal) {
            throw new ApiException(422, illegal.getMessage());
        }
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
