package com.gamma.agent.kernel.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads {@link EvalCase}s from JSON fixtures (a JSON array of cases). Fixtures are app-owned and live
 * on the classpath, neutral to any one app's data format. Jackson is allowed here (ring-2, not ring-1).
 */
public final class EvalCaseLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalCaseLoader() {}

    /** Parse a JSON array of cases. */
    public static List<EvalCase> fromJson(String json) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, EvalCase.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse eval cases", e);
        }
    }

    /** Load a JSON array of cases from a classpath resource (e.g. {@code /eval/explain-entity/cases.json}). */
    public static List<EvalCase> fromResource(String resourcePath) {
        try (InputStream in = EvalCaseLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalArgumentException("eval resource not found: " + resourcePath);
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read eval resource " + resourcePath, e);
        }
    }
}
