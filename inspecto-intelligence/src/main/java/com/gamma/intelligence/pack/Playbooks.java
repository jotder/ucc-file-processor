package com.gamma.intelligence.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads versioned prompt playbooks from {@code resources/prompts/<name>.v<N>.md} (AGT-5 P1 slice C).
 * Playbooks are the fixed recipes the investigation tier follows — the model judges the evidence
 * they frame; the deterministic tools compute it. Cached on first load (classpath resources are
 * immutable at runtime).
 */
final class Playbooks {

    static final String ROOT_CAUSE_ANALYSIS = "root_cause_analysis.v1";
    static final String IMPACT_ANALYSIS = "impact_analysis.v1";

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private Playbooks() {
    }

    /** The playbook system prompt for {@code name} (e.g. {@link #ROOT_CAUSE_ANALYSIS}). */
    static String load(String name) {
        return CACHE.computeIfAbsent(name, Playbooks::read);
    }

    private static String read(String name) {
        String path = "/prompts/" + name + ".md";
        try (InputStream in = Playbooks.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing playbook resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read playbook " + path, e);
        }
    }
}
