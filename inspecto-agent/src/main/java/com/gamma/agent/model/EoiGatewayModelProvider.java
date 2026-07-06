package com.gamma.agent.model;

import com.gamma.agent.kernel.error.ModelError;
import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelResponse;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bridges the vendored {@link ModelProvider} seam onto an eoiagent {@link LlmGateway} — the one
 * place a kernel-shaped {@link ModelRequest} becomes an eoiagent {@link ChatRequest} (agent-kernel
 * replacement, 2026-07-07). Both the local Ollama path ({@link OllamaModelProvider}) and the hosted
 * plugin ride this bridge, so the lc4j request/response mapping lives in eoiagent's
 * {@code Lc4jChatGateway}, not here.
 *
 * <h3>Lazy &amp; abstain-safe</h3>
 * Gateways are built on first {@link #generate} only — never in the constructor — and
 * {@link #available()} is the injected pure-config check, preserving the kernel's abstain-safe
 * contract (construction touches no network; unavailable providers are never called).
 *
 * <h3>JSON-constrained output</h3>
 * A non-null {@code jsonGateway} serves {@link ModelRequest#jsonFormat()} natively (Ollama
 * {@code format=json}). Without one, the uniform system-prompt constraint is appended — the same
 * wording the hosted providers have always used.
 */
public final class EoiGatewayModelProvider implements ModelProvider {

    /** Uniform JSON constraint for gateways without a native JSON mode (unchanged wording). */
    private static final String JSON_CONSTRAINT =
            "Respond with valid JSON only, no prose or code fences.";

    private final String name;
    private final BooleanSupplier available;
    private final Supplier<LlmGateway> textGateway;
    private final Supplier<LlmGateway> jsonGateway; // null = constrain via system prompt

    private volatile LlmGateway text;
    private volatile LlmGateway json;

    public EoiGatewayModelProvider(String name, BooleanSupplier available,
                                   Supplier<LlmGateway> textGateway, Supplier<LlmGateway> jsonGateway) {
        this.name = name;
        this.available = available;
        this.textGateway = textGateway;
        this.jsonGateway = jsonGateway;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        return available.getAsBoolean();
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        if (!available()) {
            throw new ModelError(name + " not available (not configured)");
        }
        try {
            boolean nativeJson = request.jsonFormat() && jsonGateway != null;
            LlmGateway gateway = nativeJson ? json() : text();

            String system = request.system();
            if (request.jsonFormat() && !nativeJson) {
                system = (system == null || system.isBlank())
                        ? JSON_CONSTRAINT : system + "\n\n" + JSON_CONSTRAINT;
            }
            List<ChatMessageRecord> messages = new ArrayList<>(2);
            if (system != null && !system.isBlank()) {
                messages.add(new ChatMessageRecord(ChatRole.SYSTEM, system, null, Map.of()));
            }
            messages.add(new ChatMessageRecord(ChatRole.USER, request.prompt(), null, Map.of()));

            ChatResult result = gateway.chat(new ChatRequest(messages, List.of(), ChatOptions.defaults()));
            return toResponse(result);
        } catch (ModelError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelError(name + " generation failed", e);
        }
    }

    /** eoiagent reports absent usage as zeros; the kernel convention is {@code -1} = unknown. */
    private static ModelResponse toResponse(ChatResult result) {
        Usage u = result.usage();
        if (u == null || u.totalTokens() == 0) {
            return ModelResponse.of(result.text());
        }
        return new ModelResponse(result.text(), u.inputTokens(), u.outputTokens());
    }

    private LlmGateway text() {
        LlmGateway g = text;
        if (g == null) {
            synchronized (this) {
                if ((g = text) == null) g = text = textGateway.get();
            }
        }
        return g;
    }

    private LlmGateway json() {
        LlmGateway g = json;
        if (g == null) {
            synchronized (this) {
                if ((g = json) == null) g = json = jsonGateway.get();
            }
        }
        return g;
    }
}
