package com.gamma.service;

import com.gamma.ops.rca.RcaTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loaded {@code *_rca.toon} templates by name (Phase 4). Extracted from {@code CollectorService} (M2
 * decomposition) as a standalone in-memory registry: it holds no lifecycle state, is not touched by the
 * service's construction or {@code close()} sequence, and is populated post-construction by
 * {@link ServiceBootstrap#fromArgs}. Backs {@code GET /rca/templates} and
 * {@code POST /objects/{id}/rca {template:<name>}} via {@code CollectorService}'s delegating accessors.
 */
final class RcaTemplateRegistry {

    private final Map<String, RcaTemplate> templates = new ConcurrentHashMap<>();

    /** Register a template keyed by name; {@code null} ignored. */
    void register(RcaTemplate template) {
        if (template != null) templates.put(template.name(), template);
    }

    /** All registered templates by name (immutable snapshot). */
    Map<String, RcaTemplate> all() {
        return Map.copyOf(templates);
    }

    /** A registered template by name, if any. */
    Optional<RcaTemplate> byName(String name) {
        return Optional.ofNullable(name == null ? null : templates.get(name.trim()));
    }
}
