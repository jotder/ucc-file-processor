package com.gamma.agent.model;

import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelResponse;

import java.util.function.Function;

/**
 * Deterministic in-memory {@link ModelProvider} for CPU-only tests. It never touches the network, so
 * golden tests run with no GPU/Ollama. The response text is computed by a supplied function of the
 * {@link ModelRequest}; {@link #generate} wraps it as a {@link ModelResponse} (token usage unknown).
 *
 * <p>Migrated to the agent-kernel {@code ModelProvider} SPI at U1: {@code generate} returns a
 * {@link ModelResponse} rather than a bare {@code String}.
 *
 * <p><b>Why this exists alongside the kernel's {@code agent-eval} {@code FakeModelProvider} (U1.6).</b>
 * The kernel fake is always-available and prompt-predicate scripted — perfect for the declarative golden
 * eval net. This UCC double is the richer app-level test util the unit tests need: it adds the modes the
 * kernel fake deliberately omits — {@link #down() unavailable}, {@link #responding(Function) dynamic
 * per-call responses} (RepairLoop retries, throw-on-call), and {@link #echo()}. It is a permanent test
 * utility, not a temporary shim.
 */
public final class FakeModelProvider implements ModelProvider {

    private final boolean available;
    private final Function<ModelRequest, String> responder;

    public FakeModelProvider(boolean available, Function<ModelRequest, String> responder) {
        this.available = available;
        this.responder = responder;
    }

    /** An available provider that echoes a marker + the prompt — handy for shape assertions. */
    public static FakeModelProvider echo() {
        return new FakeModelProvider(true, r -> "ECHO:" + r.prompt());
    }

    /** An available provider returning a fixed canned response regardless of input. */
    public static FakeModelProvider canned(String response) {
        return new FakeModelProvider(true, r -> response);
    }

    /** An available provider driven by a custom responder. */
    public static FakeModelProvider responding(Function<ModelRequest, String> responder) {
        return new FakeModelProvider(true, responder);
    }

    /** A provider that reports unavailable and throws if called (mimics no-Ollama). */
    public static FakeModelProvider down() {
        return new FakeModelProvider(false, r -> {
            throw new IllegalStateException("fake model is down");
        });
    }

    @Override public String name() { return "fake"; }
    @Override public boolean available() { return available; }

    @Override
    public ModelResponse generate(ModelRequest request) {
        if (!available) throw new IllegalStateException("fake model is down");
        return ModelResponse.of(responder.apply(request));
    }
}
