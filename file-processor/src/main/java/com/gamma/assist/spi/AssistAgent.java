package com.gamma.assist.spi;

import com.gamma.api.PublicApi;
import com.gamma.service.SourceService;

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

    /** Released on service shutdown. Default no-op. */
    @Override default void close() {}
}
