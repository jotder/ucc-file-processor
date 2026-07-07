package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Index of reusable, named components addressed by a typed <b>in-file identity</b> {@code <type>/<name>}
 * (doc §4.1) — the substrate for {@code use:} references and the dedup that lands in Phase 2. A component
 * lives at {@code registry/<typeDir>/<file>.toon}; its <b>type</b> comes from the directory
 * ({@code grammars/} → {@code grammar}) and its <b>name</b> from an in-file {@code name:}/{@code id:} key
 * (falling back to the filename stem), mirroring {@link com.gamma.service.ConfigRegistry}'s
 * identity-vs-filename reconciliation — so a component can be renamed/relocated on disk without breaking
 * {@code use:} references.
 *
 * <p>Phase-2 scope (decided): this is <b>additive flow-layer infrastructure</b> consumed by the flow world
 * ({@link PipelineNode#use()} + future {@code *_flow.toon} authoring). It does <em>not</em> touch the legacy
 * {@code *_pipeline.toon} loader. Resolution overlays a node's local config over the referenced component's
 * content ("reference, override only what's local"). v1 has no version pinning — a component resolves to its
 * current on-disk content (editing it re-feeds every referrer; that <em>is</em> the dedup).
 *
 * <p>Instances are immutable snapshots; {@link #scan(Path)} (re)builds one.
 */
@PublicApi(since = "4.3.0")
public final class ComponentRegistry {

    private static final Logger log = LoggerFactory.getLogger(ComponentRegistry.class);
    private static final String TOON = ".toon";

    /** On-disk directory (plural) → component type used in {@code use:} (singular). */
    static final Map<String, String> TYPE_BY_DIR = Map.ofEntries(
            Map.entry("connections", "connection"),
            Map.entry("grammars", "grammar"),
            Map.entry("schemas", "schema"),
            Map.entry("transforms", "transform"),   // new: extracted DataTransformer settings
            Map.entry("sinks", "sink"),              // new: extracted Output settings
            Map.entry("datasets", "dataset"),        // W3: Studio metadata kinds now persist (ComponentStore.WRITABLE_TYPES)
            Map.entry("widgets", "widget"),
            Map.entry("dashboards", "dashboard"),
            Map.entry("queries", "query"),           // W4: the query kind — POST /queries/{id}/run
            Map.entry("expectations", "expectation"),// ING-6: data-quality Expectations — /expectations*
            Map.entry("requirements", "requirement"));// UI-6/SEC-7(c): Requirements intake — /requirements*

    /** The on-disk sub-directory (plural) for a component {@code type} (e.g. {@code grammar} → {@code grammars}). */
    public static Optional<String> dirForType(String type) {
        if (type == null) return Optional.empty();
        return TYPE_BY_DIR.entrySet().stream()
                .filter(e -> e.getValue().equals(type.trim()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Whether {@code type} is a known component type ({@code connection|grammar|schema|transform|sink}). */
    public static boolean isComponentType(String type) {
        return type != null && TYPE_BY_DIR.containsValue(type.trim());
    }

    /** One indexed component: its {@code type}, in-file {@code name}, source path, and parsed content. */
    public record Component(String type, String name, Path path, Map<String, Object> content) {
        /** The {@code use:} reference that addresses this component, e.g. {@code grammar/pipe-delimited}. */
        public String ref() {
            return type + "/" + name;
        }
    }

    /** ref ({@code type/name}) → component, in scan order. */
    private final Map<String, Component> byRef;

    private ComponentRegistry(Map<String, Component> byRef) {
        this.byRef = byRef;
    }

    /** An empty registry (no components). */
    public static ComponentRegistry empty() {
        return new ComponentRegistry(Map.of());
    }

    /**
     * Scan {@code registryRoot} — the parent of {@code connections/}, {@code grammars/}, {@code schemas/},
     * {@code transforms/}, {@code sinks/} — into an indexed registry. A missing root or sub-directory is
     * simply absent (no error). An unloadable component file is warned and skipped; a duplicate
     * {@code type/name} warns and the later file wins (matching {@code ConfigRegistry}).
     */
    public static ComponentRegistry scan(Path registryRoot) {
        Map<String, Component> idx = new LinkedHashMap<>();
        if (registryRoot != null && Files.isDirectory(registryRoot)) {
            for (Map.Entry<String, String> e : TYPE_BY_DIR.entrySet()) {
                Path dir = registryRoot.resolve(e.getKey());
                if (!Files.isDirectory(dir)) continue;
                String type = e.getValue();
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(TOON))
                            .sorted()
                            .forEach(p -> load(type, p, idx));
                } catch (IOException io) {
                    log.warn("Cannot scan component dir {}: {}", dir, io.getMessage());
                }
            }
        }
        return new ComponentRegistry(Map.copyOf(idx));
    }

    private static void load(String type, Path p, Map<String, Component> idx) {
        try {
            Map<String, Object> content = ToonHelper.load(p.toString());
            String name = componentName(content, p);
            String ref = type + "/" + name;
            noteDivergence(p, name);
            Component prev = idx.put(ref, new Component(type, name, p, content));
            if (prev != null) {
                log.warn("Duplicate component '{}' from {} and {}; keeping the latter", ref, prev.path(), p);
            }
        } catch (Exception ex) {
            log.warn("Could not load component {}: {}", p, ex.getMessage());
        }
    }

    /** In-file {@code name}/{@code id}, else the filename stem. */
    private static String componentName(Map<String, Object> content, Path p) {
        for (String key : new String[]{"name", "id"}) {
            Object v = content.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString().trim();
        }
        String f = p.getFileName().toString();
        return f.endsWith(TOON) ? f.substring(0, f.length() - TOON.length()) : f;
    }

    private static void noteDivergence(Path p, String name) {
        String f = p.getFileName().toString();
        String stem = f.endsWith(TOON) ? f.substring(0, f.length() - TOON.length()) : f;
        if (!stem.equalsIgnoreCase(name)) {
            log.info("Component {} registered as in-file name '{}' (filename stem '{}')", p, name, stem);
        }
    }

    /** Resolve a {@code use: <type>/<name>} reference to its component, if registered. */
    public Optional<Component> resolve(String use) {
        return (use == null || use.isBlank()) ? Optional.empty() : Optional.ofNullable(byRef.get(use.trim()));
    }

    /** Whether {@code use} resolves to a registered component. */
    public boolean isKnown(String use) {
        return use != null && byRef.containsKey(use.trim());
    }

    /** Every registered component, in scan order. */
    public Collection<Component> all() {
        return byRef.values();
    }

    /** Registered components of one type (e.g. {@code "grammar"}), in scan order. */
    public List<Component> ofType(String type) {
        return byRef.values().stream().filter(c -> c.type().equals(type)).toList();
    }

    /**
     * The effective config for a node: the referenced component's content overlaid by the node's local
     * config (local keys win — "reference, override only what's local"). A node with no {@code use} — or an
     * unresolved one — yields its local config unchanged (the caller may then flag the dangling reference).
     */
    public Map<String, Object> effectiveConfig(PipelineNode node) {
        if (!node.hasUse()) return node.config();
        Component c = byRef.get(node.use().trim());
        if (c == null) return node.config();
        Map<String, Object> merged = new LinkedHashMap<>(c.content());
        merged.putAll(node.config());
        return merged;
    }

    /**
     * The on-disk files backing a graph's resolvable {@code use:} references — so a flow cache can fold them
     * into its mtime fingerprint (T7), reloading a flow exactly when a shared component it references changes
     * (the same pattern {@link com.gamma.service.ConfigRegistry} uses for {@code referencedFiles()}).
     * Unresolvable references (a plugin {@code ingester/<fqcn>} class ref, or a dangling name) contribute nothing.
     */
    public Set<Path> referencedPaths(PipelineGraph g) {
        Set<Path> out = new LinkedHashSet<>();
        for (PipelineNode n : g.nodes()) {
            if (!n.hasUse()) continue;
            Component c = byRef.get(n.use().trim());
            if (c != null) out.add(c.path());
        }
        return out;
    }
}
