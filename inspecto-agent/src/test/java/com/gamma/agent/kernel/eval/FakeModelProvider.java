package com.gamma.agent.kernel.eval;

import com.gamma.agent.kernel.model.ModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A deterministic {@link ModelProvider} for CPU-only tests — no Ollama, no network. Scripted with
 * prompt-predicate → response rules, evaluated in order; the first match wins, else the default
 * response (or a thrown error if none was configured). Always {@link #available()}.
 */
public final class FakeModelProvider implements ModelProvider {

    private record Rule(Predicate<ModelRequest> when, String response) {}

    private final List<Rule> rules;
    private final String defaultResponse; // null = throw when nothing matches

    private FakeModelProvider(List<Rule> rules, String defaultResponse) {
        this.rules = List.copyOf(rules);
        this.defaultResponse = defaultResponse;
    }

    public static Builder builder() { return new Builder(); }

    /** A provider that always returns the same text. */
    public static FakeModelProvider always(String response) {
        return builder().defaultResponse(response).build();
    }

    @Override public String name() { return "fake"; }
    @Override public boolean available() { return true; }

    @Override
    public ModelResponse generate(ModelRequest request) {
        for (Rule r : rules) {
            if (r.when().test(request)) return ModelResponse.of(r.response());
        }
        if (defaultResponse != null) return ModelResponse.of(defaultResponse);
        throw new IllegalStateException("FakeModelProvider: no scripted response for prompt: "
                + request.prompt());
    }

    public static final class Builder {
        private final List<Rule> rules = new ArrayList<>();
        private String defaultResponse;

        /** Respond with {@code response} when the request matches {@code when}. */
        public Builder on(Predicate<ModelRequest> when, String response) {
            rules.add(new Rule(when, response));
            return this;
        }

        /** Respond with {@code response} when the prompt contains {@code needle}. */
        public Builder onPromptContains(String needle, String response) {
            return on(req -> req.prompt() != null && req.prompt().contains(needle), response);
        }

        /** Fallback response when no rule matches (otherwise {@link #generate} throws). */
        public Builder defaultResponse(String response) {
            this.defaultResponse = response;
            return this;
        }

        public FakeModelProvider build() {
            return new FakeModelProvider(rules, defaultResponse);
        }
    }
}
