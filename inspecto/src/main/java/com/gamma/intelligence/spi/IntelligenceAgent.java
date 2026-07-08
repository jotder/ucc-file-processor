package com.gamma.intelligence.spi;

import com.gamma.intelligence.AgentAnswerSink;
import com.gamma.intelligence.AgentAskRequest;
import com.gamma.intelligence.AgentAskResult;
import com.gamma.intelligence.AgentSessionRequest;
import com.gamma.intelligence.AgentSessionResult;
import com.gamma.service.SourceService;

/**
 * Service-provider interface for the optional embedded-intelligence agent (AGT-5, P0). Successor
 * track to the reflex-layer {@link com.gamma.assist.spi.AssistAgent}: where that SPI answers one
 * single-shot skill call, this one hosts multi-turn sessions on the eoiagent deliberative loop.
 * Both stay live side by side — the reflex layer is the fallback when this module is absent.
 *
 * <p>The implementation lives in the separate {@code file-processor-intelligence} module so the
 * core fat-JAR stays dependency-lean. When that module — and a provider declared via
 * {@code META-INF/services/com.gamma.intelligence.spi.IntelligenceAgent} — is on the classpath,
 * {@link SourceService} discovers it with {@link java.util.ServiceLoader} at startup. A provider
 * can also be supplied explicitly via {@link SourceService#registerIntelligenceAgent} (used by
 * tests).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #init(SourceService)} — called once, before {@link SourceService#start()}.</li>
 *   <li>{@link #start()} — called after the service has started.</li>
 *   <li>{@link #close()} — called on service shutdown.</li>
 * </ol>
 */
public interface IntelligenceAgent extends AutoCloseable {

    /** Stable, human-readable name for logs (e.g. {@code "inspecto-intelligence"}). */
    String name();

    /**
     * Wire the agent to the running service. Called exactly once, before
     * {@link SourceService#start()}. Implementations should capture only what they need and
     * return quickly; defer model/platform assembly to {@link #start()}.
     */
    void init(SourceService service);

    /** Called after {@link SourceService#start()}. Default no-op. */
    default void start() {}

    /** Open a new session for the caller described by {@code request}. */
    AgentSessionResult openSession(AgentSessionRequest request);

    /**
     * Ask a question on an open session. Implementations throw {@link IllegalArgumentException}
     * for an unknown/closed {@code sessionId} — the control plane maps that to HTTP 404.
     */
    AgentAskResult ask(String sessionId, AgentAskRequest request);

    /**
     * Ask a question, streaming tokens to {@code sink} as they're produced (AGT-5, hardening pass).
     * Additive default: delivers the whole {@link #ask} answer as a single {@code onComplete} call
     * (no real per-token streaming) and reports an unknown session via {@link AgentAnswerSink#onError}
     * instead of throwing — so an implementation that doesn't support streaming keeps compiling and
     * degrades to a post-hoc "stream" rather than breaking the route.
     */
    default void askStream(String sessionId, AgentAskRequest request, AgentAnswerSink sink) {
        try {
            sink.onComplete(ask(sessionId, request));
        } catch (IllegalArgumentException e) {
            sink.onError(e.getMessage());
        }
    }

    /** Released on service shutdown. Default no-op. */
    @Override default void close() {}
}
