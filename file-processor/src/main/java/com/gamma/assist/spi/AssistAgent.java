package com.gamma.assist.spi;

import com.gamma.api.PublicApi;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.Diagnosis;
import com.gamma.service.SourceService;

import java.util.List;
import java.util.Map;

/**
 * Service-provider interface for the optional embedded assist agent (v3.0, milestone M0).
 *
 * <p>The agent implementation lives in the separate {@code file-processor-agent} module so
 * the core fat-JAR stays dependency-lean. When that module — and a provider declared via
 * {@code META-INF/services/com.gamma.assist.spi.AssistAgent} — is on the classpath,
 * {@link SourceService} discovers it with {@link java.util.ServiceLoader} at startup and
 * wires it <em>in-process</em>. A provider can also be supplied explicitly via
 * {@link SourceService#registerAgent(AssistAgent)} (used by tests).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #init(SourceService)} — called once, <b>before</b> {@link SourceService#start()},
 *       so the agent can subscribe to {@link SourceService#eventBus()} before the first poll
 *       cycle and capture the typed handles it needs ({@link SourceService#reports()},
 *       {@code enrichmentService()}, {@code jobService()}, {@code statusStore()}).</li>
 *   <li>{@link #start()} — called after the service has started.</li>
 *   <li>{@link #close()} — called on service shutdown.</li>
 * </ol>
 * {@code start()} and {@code close()} are no-ops by default, so a provider implements only
 * what it needs. The host never lets the agent perform autonomous writes — agents propose;
 * validated control-plane endpoints (applied with a human credential) dispose.
 *
 * @since 3.0.0
 */
@PublicApi(since = "3.0.0")
public interface AssistAgent extends AutoCloseable {

    /** Stable, human-readable name for logs and the control surface (e.g. {@code "ucc-assist"}). */
    String name();

    /**
     * Wire the agent to the running service. Called exactly once, before
     * {@link SourceService#start()}. Implementations should capture only what they need and
     * return quickly; defer any heavy work (model warm-up, etc.) to {@link #start()}.
     *
     * @param service the host service, for typed access to its subsystems and event bus
     */
    void init(SourceService service);

    /** Called after {@link SourceService#start()}. Default no-op. */
    default void start() {}

    /**
     * Handle one assist request and return a validated, ready-to-surface result (v3.3.0). The
     * {@code request.intent()} selects the skill; an agent that doesn't recognise it returns
     * {@link AssistResult#unsupported(String)} (the default). A skill whose model is unavailable
     * returns {@link AssistResult#unavailable(String, String)}. The control plane maps the
     * {@link AssistResult.Status} onto an HTTP status.
     *
     * <p>This is an <b>additive</b> default so existing providers (e.g. the M0 no-op) keep
     * compiling and behaving unchanged. Implementations must never throw for an unknown intent or
     * a down model — they report it through the returned {@link AssistResult}.
     *
     * @since 3.3.0
     */
    default AssistResult assist(AssistRequest request) {
        return AssistResult.unsupported(request.intent());
    }

    /**
     * The most recent failure diagnoses produced by the agent's event-driven reactor (v3.7.0, M7),
     * newest first, capped at {@code limit}. Backs the read-only {@code GET /assist/diagnoses} route.
     *
     * <p>This is an <b>additive</b> default returning an empty list, so existing providers (the M0
     * no-op, any embedder) keep compiling and behaving unchanged; an agent that does event-driven
     * diagnosis (e.g. {@code ucc-assist}) overrides it to expose its in-memory diagnosis store.
     *
     * @since 3.7.0
     */
    default List<Diagnosis> recentDiagnoses(int limit) {
        return List.of();
    }

    /**
     * The agent's model-provider settings as a masked, JSON-ready view (v4.1) — current provider,
     * tier→model map, key <em>presence</em> (never the key itself), and the providers selectable on
     * this classpath. Backs the read-only {@code GET /assist/settings} route.
     *
     * <p>Additive default: an agent without configurable model routing reports
     * {@code {"supported": false}} and the settings screen disables itself.
     *
     * @since 4.1.0
     */
    default Map<String, Object> settings() {
        return Map.of("supported", false);
    }

    /**
     * Apply new model-provider settings (v4.1): validate, persist (never persisting a raw API key),
     * and re-route live. Backs {@code POST /assist/settings} (scope {@code assist.write}).
     * Implementations signal a bad request with {@link IllegalArgumentException} (mapped to 400).
     *
     * @since 4.1.0
     */
    default Map<String, Object> updateSettings(Map<String, Object> changes) {
        return Map.of("supported", false);
    }

    /**
     * Verify the configured providers with a minimal round-trip per model tier (v4.1) and report
     * per-tier {@code ok}/{@code latencyMs}/{@code error}. Backs {@code POST /assist/settings/test}.
     *
     * @since 4.1.0
     */
    default Map<String, Object> testSettings() {
        return Map.of("supported", false);
    }

    /** Released on service shutdown. Default no-op. */
    @Override default void close() {}
}
