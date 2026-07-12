package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.event.EventLog;
import com.gamma.service.SourceService;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
 * <h3>Authentication (W6)</h3>
 * The core (Personal edition) is <b>auth-free</b> — every route is open, exactly as before. Authentication
 * and authorization are an <em>edition</em> concern: {@link #dispatch} looks up an {@link Authenticator}
 * via {@link Authenticators} (a {@code ServiceLoader} seam); when the Standard edition's
 * {@code inspecto-security} module is absent, the lookup is empty and nothing is enforced. When it is
 * present, every route outside the health/bootstrap probe surface requires a valid credential
 * ({@code 401 UNAUTHENTICATED} on failure); write routes additionally declare a required capability via
 * {@link ApiContext#withCapability} ({@code 403 PERMISSION_DENIED} on a missing grant). See
 * {@code docs/EDITIONS.md}.
 *
 * <h3>Per-space routing</h3>
 * One process hosts many isolated spaces (see {@link SpaceManager}). Every route below may be addressed under a
 * {@code /spaces/{id}} prefix ({@code GET /spaces/acme/runs}); {@link #dispatch} strips the prefix, binds the
 * request to that space, and matches the <em>unchanged</em> patterns against the remainder, so each space's
 * service/stores/events/metric-label resolve in isolation. An unknown id is a {@code 404}. {@code /health},
 * {@code /ready}, {@code /metrics} (and the future {@code /spaces} CRUD group) stay un-prefixed and server-global;
 * an un-prefixed API path resolves the {@code default} (or sole) space, so single-space callers are unaffected.
 *
 * <h3>Versioned API (v1) — v4.8.0</h3>
 * The same route table is additionally served under {@code /api/v1/…} with the v1 transport
 * contract (docs/superpower/api-contract-design.md): responses wrapped in the
 * {@code {data, metadata, links, diagnostics}} envelope ({@link Envelope}), errors as structured
 * objects with machine-readable codes ({@link ErrorCodes}), a per-request {@code Correlation-ID}
 * (issued when absent; echoed on every response, legacy included), and gzip content negotiation.
 * Unversioned routes are byte-for-byte unchanged (the SPA still calls them via the plain
 * {@code /api} alias) and are frozen as legacy aliases until the UI migrates to v1.
 *
 * <h3>Routes</h3>
 * <pre>
 *   GET  /health                              liveness (open)
 *   GET  /ready                               readiness (open)
 *   GET  /bootstrap                           platform bootstrap: edition/features/config-specs/enums/spaces/session (ETag'd) [v4.8.0]
 *   POST /auth/exchange                       redeem an OIDC code (PKCE) → access token + httpOnly refresh cookie [v4.8.0, 503 on Personal]
 *   POST /auth/refresh                        mint a fresh access token from the refresh cookie      [v4.8.0, 503 on Personal]
 *   POST /auth/logout                         revoke (best-effort) + clear the session cookie        [v4.8.0, 503 on Personal]
 *   GET  /spaces                              list hosted spaces (manifests)               [v4.7.0]
 *   POST /spaces                              body {id,display_name?,description?} — create + boot a space [v4.7.0]
 *   PUT  /spaces/{id}                         body {display_name?,description?} — rename/re-describe (not 'default') [v4.10.0]
 *   DELETE /spaces/{id}[?purge=true]          deregister + drain a space; purge also deletes its files [v4.7.0]
 *   GET  /runs                           list pipelines + state
 *   POST /runs                           body {"configPath":"…"} — register a new pipeline   [v4.1.0]
 *   POST /runs/{name}/trigger            run one pipeline once
 *   POST /runs/{name}/pause              pause (poll cycle skips it)
 *   POST /runs/{name}/resume             resume
 *   GET  /runs/{name}/commits            committed batch ids
 *   GET  /runs/{name}/batches            batch audit rows
 *   GET  /runs/{name}/files              per-file audit rows
 *   GET  /runs/{name}/lineage[?batchId=] input→output lineage rows
 *   GET  /runs/{name}/quarantine         quarantined inputs + reason
 *   POST /runs/{name}/reprocess          body {"batchId":"…"} — replay a batch
 *   POST /trigger                             run all pipelines once
 *   POST /validate                            body {"configPath":"…"} or {"type":…,"config":{…}} — findings
 *   GET  /status                              live status snapshot (all pipelines)        [v2.8.0]
 *   GET  /report[?from=&to=]                  service-wide batch-audit report             [v2.8.0]
 *   GET  /runs/{name}/report[?from=&to=] batch-audit report for one pipeline         [v2.8.0]
 *   GET  /jobs                                list config-driven jobs + last/next run      [v2.8.0]
 *   GET  /jobs/{name}/runs                    recent run history for a job                 [v2.8.0]
 *   POST /jobs/{name}/trigger                 run a job once now (v1: 202 + runId + Location)  [v2.8.0]
 *   GET  /jobs/runs/{runId}                   poll one job run's status (RUNNING → terminal)   [v4.8.0]
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
 *   GET  /settings/branding                   per-space UI branding {logoDataUrl,caption,footerText}  [v4.10.0]
 *   PUT  /settings/branding                   replace per-space UI branding (write-root gated)         [v4.10.0]
 *   POST /queries/{id}/run                     run a persisted query ($-params resolved, Result Set contract) [v4.8.0]
 *   GET  /events[?limit=]                     recent events, newest-first (live tail)       [v4.2.0]
 *   GET  /events/search[?level=&type=&pipeline=&correlationId=&q=&from=&to=&limit=&offset=] filtered events [v4.2.0]
 *   GET  /events/{id}                         one event by id                               [v4.2.0]
 *   GET  /events/export[?format=csv&…filters] export matching events (csv | json)           [v4.2.0]
 *   GET  /events/views                        list operator-saved views                     [v4.2.0]
 *   POST /events/views                        body {name,level?,type?,pipeline?,q?,…} — upsert a view [v4.2.0]
 *   POST /events/views/{name}/delete          delete a saved view                           [v4.2.0]
 *   GET  /objects[?type=&status=&severity=&assignee=&owner=&correlationId=&q=&limit=&offset=] filtered objects [v4.3.0]
 *   POST /objects                             body {type?,title,severity?,priority?,assignee?,dueAt?|dueInMinutes?,…} — create (INCIDENT) [v4.4.0]
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
 *   POST /connections                         body {id,connector,…} — create (write-root gated); 409 if id exists [v4.2.0]
 *   PUT  /connections/{id}                    replace a profile (masked secrets preserved); 404 if unknown [v4.2.0]
 *   DELETE /connections/{id}                  remove a profile; 404 if unknown, 409 if in use by a pipeline [v4.2.0]
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
 *   <li><b>HTTPS (W6)</b> — set {@code -Dhttps.keystore=<PKCS12 path>} (+
 *       {@code -Dhttps.keystore.password=<pw>}) to serve over TLS 1.3 instead of plain HTTP. Unset
 *       (the default) ⇒ plain HTTP, byte-for-byte unchanged (Personal edition).</li>
 * </ul>
 */
@PublicApi(since = "2.4.0")
public final class ControlApi implements AutoCloseable, ApiContext {

    private static final Logger log = LoggerFactory.getLogger(ControlApi.class);
    private static final Object HANDLED = ApiContext.HANDLED;

    /**
     * Captures a per-space request prefix {@code /spaces/<id>/<rest>}: group 1 = the space id (same charset as
     * {@link SpaceId}), group 2 = the remaining path (with leading {@code /}) matched against the unchanged route
     * table. {@code /spaces} and {@code /spaces/<id>} with no trailing path deliberately do <em>not</em> match —
     * they stay server-global for the {@code SpaceRoutes} CRUD group.
     */
    private static final Pattern SPACE_PREFIX = Pattern.compile("^/spaces/([a-z0-9][a-z0-9-]{0,62})(/.*)$");

    /** Routes that stay open even when the Standard edition's security module is active (W6): liveness/
     *  readiness/metrics probes carry no credentials; {@code /bootstrap} is how the SPA discovers it
     *  needs to start the OIDC redirect in the first place (its own {@code session.authenticated} reports
     *  {@code false} rather than 401); and the {@code /auth/*} session routes (W6d) run <em>before</em> a
     *  Bearer token exists — their credential is the code being redeemed or the {@code httpOnly} refresh
     *  cookie. Everything else requires a valid {@link Authenticator} result. */
    private static final java.util.Set<String> PUBLIC_PATHS = java.util.Set.of(
            "/health", "/ready", "/metrics", "/bootstrap",
            "/auth/exchange", "/auth/refresh", "/auth/logout");

    private final HttpServer http;
    private final SpaceManager spaces;
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

    /** Per-instance {@code Idempotency-Key} replay cache for retryable writes (W5). */
    private final Idempotency.Store idempotency = new Idempotency.Store();

    /**
     * API-5 sunset flip: {@code -Dapi.legacy.routes=off} answers <b>410 Gone</b> on the unversioned legacy
     * route aliases once a deployment's soak shows zero residual demand (the
     * {@code inspecto_legacy_api_requests_total} signal, which keeps counting through the off-window).
     * Default {@code on} serves them unchanged. The always-unversioned infra probes
     * (health/ready/metrics) are exempt — they have no v1 semantics.
     */
    private final boolean legacyRoutesOff;
    /** Pre-formatted RFC 8594 {@code Sunset} header from {@code -Dapi.legacy.sunset=YYYY-MM-DD};
     *  {@code null} until an operator signs a retirement date. */
    private final String legacySunset;

    /**
     * Control plane over a single running service — wrapped as the {@code default} space. The long-standing
     * single-tenant entry point (and every test); behaviour is unchanged.
     *
     * @param service the running service to control
     * @param port    TCP port (0 = ephemeral; read back via {@link #port()})
     */
    public ControlApi(SourceService service, int port) throws IOException {
        this(SpaceManager.single(service), port);
    }

    /**
     * Control plane over a {@link SpaceManager} hosting one or more spaces. Until the {@code /spaces/{id}} request
     * seam lands, every request resolves the manager's {@linkplain SpaceManager#current() current} (default) space.
     *
     * @param spaces the hosted spaces to control
     * @param port   TCP port (0 = ephemeral; read back via {@link #port()})
     */
    public ControlApi(SpaceManager spaces, int port) throws IOException {
        this.spaces = spaces;
        String cors  = System.getProperty("control.cors");
        this.corsOrigin = blank(cors) ? null : cors.trim();
        String ui    = System.getProperty("ui.dir");
        this.uiDir   = blank(ui) ? null : Path.of(ui.trim()).toAbsolutePath().normalize();
        String wr    = System.getProperty("assist.write.root");
        this.writeRoot = blank(wr) ? null : Path.of(wr.trim()).toAbsolutePath().normalize();
        this.legacyRoutesOff = "off".equalsIgnoreCase(System.getProperty("api.legacy.routes", "on").trim());
        this.legacySunset = sunsetHeader(System.getProperty("api.legacy.sunset"));
        this.http    = createServer(port);
        this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // Teach DatasetRelation to resolve shared/<owner>/<item> refs to the owner's Exchange snapshot,
        // grant-checked for the calling space (a no-op resolver until installed — fail-closed).
        com.gamma.query.SharedRefResolver.install(new ExchangeRefResolver(spaces));
        registerRoutes();
        this.http.createContext("/", this::dispatch);
        // Fail-closed at the edge (W6): resolve the edition's Authenticator now, not on the first
        // request, so a misconfigured Standard deployment (e.g. missing -Dauth.oidc.jwksUri, which the
        // security module's no-arg constructor rejects) fails to boot instead of silently accepting
        // traffic. A no-op on Personal — the lookup just resolves empty.
        Authenticators.active();
    }

    /**
     * Plain HTTP by default (Personal edition, unchanged). Set {@code -Dhttps.keystore=<PKCS12 path>}
     * (+ {@code -Dhttps.keystore.password=<pw>}) to serve over TLS 1.3 instead (Standard edition,
     * docs/EDITIONS.md); pure JDK ({@link HttpsServer} + {@code javax.net.ssl}), no new dependency.
     */
    private static HttpServer createServer(int port) throws IOException {
        String keystore = System.getProperty("https.keystore");
        if (blank(keystore)) return HttpServer.create(new InetSocketAddress(port), 0);
        char[] password = System.getProperty("https.keystore.password", "").toCharArray();
        try (var in = Files.newInputStream(Path.of(keystore.trim()))) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            javax.net.ssl.KeyManagerFactory kmf =
                    javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
            ssl.init(kmf.getKeyManagers(), null, null);
            HttpsServer https = HttpsServer.create(new InetSocketAddress(port), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(ssl));
            return https;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("failed to configure HTTPS from -Dhttps.keystore=" + keystore, e);
        }
    }

    public int port() { return http.getAddress().getPort(); }

    public void start() {
        http.start();
        if (Authenticators.active().isPresent())
            log.info("ControlApi started on port {} (Standard edition — authentication enforced via {})",
                    port(), Authenticators.active().get().getClass().getName());
        else
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
        // Multi-space mode: -Dspaces.root points at a container dir of spaces/<id>/; each is booted in isolation.
        // Legacy single-tenant mode (no -Dspaces.root): build the one default space from the CLI config args.
        String spacesRoot = System.getProperty("spaces.root");
        SpaceManager spaces;
        if (spacesRoot != null && !spacesRoot.isBlank()) {
            spaces = SpaceManager.discover(Path.of(spacesRoot.trim()));
            if (spaces.size() == 0) {
                System.err.println("No spaces (a dir with a config/ subtree) found under -Dspaces.root=" + spacesRoot);
                System.exit(1);
            }
        } else {
            if (args.length < 1) {
                System.err.println("Usage: ControlApi [-Dcontrol.port=8080] [-Dspaces.root=DIR] "
                        + "[-Dservice.poll.seconds=N] [-Dservice.max.runs=M] <pipeline.toon | dir> [more ...]");
                System.exit(1);
            }
            spaces = SpaceManager.single(SourceService.fromArgs(args));
        }
        int port = Integer.getInteger("control.port", 8080);
        ControlApi api = new ControlApi(spaces, port);

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            spaces.close();
            latch.countDown();
        }, "inspecto-shutdown"));
        spaces.startAll();
        api.start();
        latch.await();   // block until SIGTERM/SIGINT
    }

    // ── routes ───────────────────────────────────────────────────────────────────

    private void registerRoutes() {
        get ("/health", (e, m) -> Map.of("status", "UP"));
        // MNT-15: per-subsystem health — deeper than the liveness probe, auth-gated (not a public path).
        get ("/health/details", (e, m) -> HealthDetails.of(this));
        get ("/ready",  (e, m) -> Map.of("status", "READY", "pipelines", service().pipelines().size()));
        // Prometheus scrape endpoint — text exposition, open (scrapers don't carry tokens)
        get("/metrics", (e, m) ->
                respondText(e, com.gamma.metrics.MetricRegistry.global().scrape()));

        // Feature route modules extracted from this class (see RouteModule); each owns its own routes + docs.
        for (RouteModule module : List.of(
                new BootstrapRoutes(), new AuthRoutes(),
                new SpaceRoutes(), new ExchangeRoutes(), new DataSourceRoutes(),
                new RunRoutes(),
                new ConnectionRoutes(), new ViewRoutes(), new PipelineRoutes(), new ComponentRoutes(), new BundleRoutes(),
                new EventRoutes(), new ObjectRoutes(), new QueueRoutes(), new TagRoutes(), new CatalogRoutes(), new ConfigRoutes(),
                new QueryRoutes(), new BiRoutes(), new ShareRoutes(), new InvRoutes(),
                new ExpectationRoutes(), new RequirementRoutes(),
                new JobRoutes(), new SignalRoutes(), new LineageRoutes(), new EnrichmentRoutes(), new AlertRoutes(), new DecisionRoutes(), new AcquisitionRoutes(),
                new NotificationRoutes(), new SettingsRoutes(),
                new AssistRoutes(), new AgentRoutes()))
            module.register(this);
    }

    // ── dispatch ───────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange ex) throws IOException {
        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        // Correlation (v1 contract, applied to EVERY request — v4.8.0): honour a caller-supplied
        // Correlation-ID, else issue one; echo it as a response header, carry it on the exchange for
        // the v1 envelope/error bodies, and put it on the SLF4J MDC so events bridged during this
        // request (EventStoreAppender reads mdc "correlationId") tie back to the request. Engine-typed
        // events keep their own explicit correlation (batch/run ids) — the builder value wins there.
        String cid = ex.getRequestHeaders().getFirst("Correlation-ID");
        cid = (cid == null || cid.isBlank()) ? java.util.UUID.randomUUID().toString() : cid.trim();
        ex.setAttribute(ApiContext.ATTR_CORRELATION_ID, cid);
        ex.getResponseHeaders().set("Correlation-ID", cid);
        MDC.put("correlationId", cid);
        // Versioned-API seam (v4.8.0): "/api/v1/…" marks this exchange for the v1 transport contract
        // (Envelope + structured errors, docs/superpower/api-contract-design.md) and is matched against
        // the same route table. Checked before the plain "/api" strip ("/api/v1/…" also starts with it).
        if (path.equals("/api/v1") || path.startsWith("/api/v1/")) {
            ex.setAttribute(ApiContext.ATTR_V1, Boolean.TRUE);
            ex.setAttribute(ApiContext.ATTR_START_NANOS, System.nanoTime());
            ex.setAttribute(ApiContext.ATTR_SELF_PATH, path);
            path = path.length() == 7 ? "/" : path.substring(7);
        }
        // Accept an optional "/api" prefix so a single SPA build works in both deployment modes:
        // behind the ng-serve dev proxy (which rewrites "/api" → "") and when served same-origin by
        // ControlApi itself (no proxy). The Angular app addresses every route as "/api/...", so strip
        // the prefix here before route matching. Static assets never carry "/api", so they're untouched.
        else if (path.startsWith("/api/")) path = path.substring(4);
        else if (path.equals("/api")) path = "/";
        if (corsOrigin != null) applyCors(ex);     // rides every response written below
        boolean spaceBound = false;
        try {
            // CORS preflight: answer before route matching (no token, no body).
            if (corsOrigin != null && "OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            // Idempotency-Key (W5): a keyed write whose response is already cached replays it verbatim,
            // skipping the handler entirely — so a retried trigger/create does not run twice. Keyed on the
            // raw request path so /api/v1 and legacy surfaces don't share entries. A miss marks the exchange
            // so ApiContext.respondJson captures the first response.
            String idemKey = Idempotency.keyFor(ex, method, ex.getRequestURI().getPath());
            if (idemKey != null) {
                Idempotency.Entry hit = idempotency.get(idemKey);
                if (hit != null) {
                    Idempotency.replay(ex, hit);
                    AuditTrail.record(ex, method, path, hit.status());
                    return;
                }
                ex.setAttribute(ApiContext.ATTR_IDEMPOTENCY_STORE, idempotency);
                ex.setAttribute(ApiContext.ATTR_IDEMPOTENCY_KEY, idemKey);
            }
            // Per-space request seam: a "/spaces/{id}/<rest>" path binds this request to that space and is then
            // matched as "/<rest>" against the unchanged route table — so RouteModules never see the prefix. An
            // unknown id is a 404. The bound space is carried on the SLF4J MDC (the same per-space routing key the
            // engine singletons read — Stage 3a), so service()/writeRoot() and every space-scoped singleton resolve
            // to it for the life of this request only. "default" sets no MDC (the fallback namespace everywhere),
            // keeping single-space output byte-identical. /health, /ready, /metrics and /spaces CRUD stay un-prefixed.
            Matcher sp = SPACE_PREFIX.matcher(path);
            if (sp.matches()) {
                String id = sp.group(1);
                if (spaces.space(SpaceId.of(id)).isEmpty()) {
                    respond(ex, 404, Map.of("error", "no such space '" + id + "'"));
                    return;
                }
                path = sp.group(2);
                if (!EventLog.DEFAULT_SPACE_ID.equals(id)) {
                    MDC.put(EventLog.SPACE_MDC_KEY, id);
                    spaceBound = true;
                }
            }
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.pattern.matcher(path);
                if (!m.matches()) continue;
                pathMatched = true;
                if (!r.method.equals(method)) continue;
                // API-5 sunset: a business route reached on the unversioned legacy surface either gets the
                // deprecation signalling headers (default) or, once the deployment flips
                // -Dapi.legacy.routes=off after its soak, a 410 pointing at /api/v1. The usage metric keeps
                // counting either way, so residual demand stays visible through the off-window.
                boolean legacySurface = !ApiContext.v1(ex) && !isInfraRoute(path);
                if (legacySurface && legacyRoutesOff) {
                    recordLegacyUsage(ex, method, path, r);
                    if (!"GET".equals(method)) AuditTrail.accessDenied(ex, method, path, 410);
                    respond(ex, 410, Map.of("error",
                            "the unversioned legacy API surface is retired here (api.legacy.routes=off) — use /api/v1"));
                    return;
                }
                if (legacySurface) markDeprecated(ex);
                authenticate(ex, path);
                Object result = r.handler.handle(ex, m);
                if (result != HANDLED) respond(ex, 200, result);
                AuditTrail.record(ex, method, path, 200);   // audit successful state-changing requests
                recordLegacyUsage(ex, method, path, r);     // W7 sunset signal (non-v1 calls to versioned routes)
                return;
            }
            // No API route matched the path: a GET may be an SPA asset / deep link (PUBLIC).
            if (!pathMatched && "GET".equals(method) && serveStatic(ex, path)) return;
            int status = pathMatched ? 405 : 404;
            // A non-GET attempt at a forbidden/unknown route (or a disallowed method on a read-only
            // route — the append-only immutability guard) is the auth-free analogue of a 401/403.
            if (!"GET".equals(method)) AuditTrail.accessDenied(ex, method, path, status);
            respond(ex, status, Map.of("error", pathMatched ? "method not allowed" : "not found"));
        } catch (ApiException ae) {
            if (ae.errorCode != null) ex.setAttribute(ApiContext.ATTR_ERROR_CODE, ae.errorCode);
            respond(ex, ae.status, Map.of("error", ae.getMessage()));
        } catch (Exception e) {
            log.error("{} {} failed", method, path, e);
            respond(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            MDC.remove("correlationId");
            if (spaceBound) MDC.remove(EventLog.SPACE_MDC_KEY);
            ex.close();
        }
    }

    /** AuthN gate (W6): a no-op when no {@link Authenticator} is on the classpath (Personal edition —
     *  {@link Authenticators#active()} is empty), so Personal behaviour is byte-for-byte unchanged. When
     *  the Standard edition's security module is present, a {@link #PUBLIC_PATHS} route (bootstrap/health)
     *  authenticates <em>optionally</em> — a Subject is attached when credentials resolve one (so
     *  {@code /bootstrap} reports the real session for an already-logged-in caller), but missing/invalid
     *  credentials there is not an error. Every other route requires a valid credential; a miss is
     *  {@code 401 UNAUTHENTICATED}. On success the resolved {@link Subject} is attached to the exchange
     *  for {@link ApiContext#actor}, {@code requireCapability} and the v1 envelope's {@code permissions}. */
    private void authenticate(HttpExchange ex, String path) {
        // BI-6: /public/dashboards/* carries its own credential — the HMAC share token in the path,
        // verified (signature + expiry) by ShareRoutes. Sharing as a whole is disabled unless
        // -Dbi.share.secret is configured, so this exemption is inert by default.
        boolean required = !PUBLIC_PATHS.contains(path) && !path.startsWith("/public/dashboards/");
        Authenticators.active().ifPresent(a -> {
            // SEC-7(a): on Standard the acting identity is authoritative from the authenticated Subject; a
            // client-supplied X-Actor header is an attempted actor spoof and is rejected outright. (Personal
            // has no Authenticator, so this branch never runs there and X-Actor stays the historic actor.)
            String spoof = ex.getRequestHeaders().getFirst("X-Actor");
            if (spoof != null && !spoof.isBlank())
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                        "X-Actor is not accepted on this edition; the actor is taken from the authenticated session");
            java.util.Optional<Subject> subject = a.authenticate(ex);
            if (subject.isPresent()) ex.setAttribute(ApiContext.ATTR_SUBJECT, subject.get());
            else if (required) throw new ApiException(401, ErrorCodes.UNAUTHENTICATED, "authentication required");
        });
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
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Token, Correlation-ID");
        h.set("Access-Control-Expose-Headers", "Correlation-ID");
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

    /**
     * The space this request is bound to: the one named by the request's space MDC (set by {@link #dispatch} for a
     * {@code /spaces/{id}} path), or — for an un-prefixed request — the manager's
     * {@linkplain SpaceManager#current() current} (default/first) space.
     */
    private SpaceContext currentContext() {
        return spaces.space(SpaceId.of(EventLog.currentSpaceId())).orElseGet(spaces::current);
    }

    @Override
    public SourceService service() { return currentContext().service(); }

    @Override
    public SpaceManager spaces() { return spaces; }

    /**
     * The write-jail root for {@code POST /config/write} and disk-registration, scoped to the bound space: a
     * discovered space writes into its own {@code config/} tree (hot-reloaded by {@code ConfigRegistry.rebuild});
     * the legacy/default space keeps the server-global {@code -Dassist.write.root} (unchanged single-tenant behaviour).
     */
    @Override
    public Path writeRoot() {
        Path spaceConfig = currentContext().root().config();
        return spaceConfig != null ? spaceConfig : writeRoot;
    }

    @Override
    public Path dataRoot() {
        String dir = currentContext().root().dataDir();
        return (dir == null || dir.isBlank()) ? null : Path.of(dir);
    }

    // Route registration. The core (Personal edition) is auth-free — every route is open.
    // Standard/Enterprise editions re-introduce authorization out-of-band via the security module.
    @Override public void get (String pattern, Handler h) { routes.add(new Route("GET",    Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void post(String pattern, Handler h) { routes.add(new Route("POST",   Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void put   (String pattern, Handler h) { routes.add(new Route("PUT",    Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void patch (String pattern, Handler h) { routes.add(new Route("PATCH",  Pattern.compile("^" + pattern + "$"), h)); }
    @Override public void delete(String pattern, Handler h) { routes.add(new Route("DELETE", Pattern.compile("^" + pattern + "$"), h)); }

    /**
     * W7 sunset signal: a successful call that matched a versioned business route but did NOT arrive on the
     * {@code /api/v1} surface is a legacy-alias use. Counted per route (bounded cardinality — the route
     * pattern) so an operator can see, per real deployment, whether anything still depends on the unversioned
     * aliases before they are removed (removal stays gated on that soak — api-contract-design.md §10 W7). The
     * always-unversioned infra probes (health/ready/metrics) are excluded — they have no v1 semantics.
     */
    private void recordLegacyUsage(HttpExchange ex, String method, String path, Route r) {
        if (ApiContext.v1(ex) || isInfraRoute(path)) return;
        com.gamma.metrics.MetricRegistry.global().inc("inspecto_legacy_api_requests_total",
                "Calls to the unversioned legacy route aliases (pre-/api/v1) — the W7 sunset signal",
                Map.of("route", r.pattern.pattern()));
        log.debug("legacy (non-v1) API call: {} {} — migrate to /api/v1", method, path);
    }

    private static boolean isInfraRoute(String path) {
        return path.equals("/health") || path.equals("/ready")
                || path.equals("/metrics") || path.equals("/metrics/acquisition");
    }

    /**
     * API-5 sunset signalling on every legacy (non-v1) response: {@code Deprecation} (RFC 9745 — pinned to
     * 2026-07-07, the day the SPA finished migrating to {@code /api/v1} and the unversioned aliases became
     * legacy), a {@code Link} to the successor surface, and {@code Sunset} (RFC 8594) once an operator signs
     * a retirement date. Set before the handler runs, so the headers ride whatever response it writes.
     */
    private void markDeprecated(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Deprecation", "@1783382400");   // 2026-07-07T00:00:00Z (W7 migration complete)
        h.set("Link", "</api/v1>; rel=\"successor-version\"");
        if (legacySunset != null) h.set("Sunset", legacySunset);
    }

    /** Parse {@code -Dapi.legacy.sunset=YYYY-MM-DD} into an RFC 8594 HTTP-date, or null (unset/unparsable). */
    private static String sunsetHeader(String isoDate) {
        if (blank(isoDate)) return null;
        try {
            return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    java.time.LocalDate.parse(isoDate.trim()).atStartOfDay(java.time.ZoneOffset.UTC));
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("[CONFIG] Ignoring unparsable -Dapi.legacy.sunset '{}' (want YYYY-MM-DD)", isoDate);
            return null;
        }
    }

    private record Route(String method, Pattern pattern, Handler handler) {}
}
