package com.gamma.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD store for {@link SavedView}s, backing the {@code /events/views} routes. Holds views in an
 * insertion-ordered in-memory map and, when given a backing {@code file}, persists them as a JSON
 * array on every mutation so operator-saved views survive a restart.
 *
 * <p>When constructed with a {@code null} file the store is purely in-memory (no side effects) — the
 * default for tests and for the lean fat-JAR until {@code -Devents.views.file} is set.
 *
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public final class SavedViewStore {

    private static final Logger log = LoggerFactory.getLogger(SavedViewStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};

    private final Path file;
    private final Map<String, SavedView> views = new LinkedHashMap<>();

    /** In-memory only (no persistence). */
    public SavedViewStore() {
        this(null);
    }

    /** Persist to {@code file} (loaded now if present); {@code null} ⇒ in-memory only. */
    public SavedViewStore(Path file) {
        this.file = file;
        load();
    }

    /** All views, JSON-ready, in insertion order. */
    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>(views.size());
        for (SavedView v : views.values()) out.add(v.toMap());
        return out;
    }

    public synchronized SavedView get(String name) {
        return views.get(name);
    }

    /** Upsert {@code view} (keyed by name) and persist. Returns the stored view. */
    public synchronized SavedView save(SavedView view) {
        views.put(view.name(), view);
        persist();
        return view;
    }

    /** Remove the named view; returns {@code true} if it existed. Persists when something changed. */
    public synchronized boolean delete(String name) {
        boolean removed = views.remove(name) != null;
        if (removed) persist();
        return removed;
    }

    // ── persistence (best-effort; never fatal) ─────────────────────────────────────

    private void persist() {
        if (file == null) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(list()));
        } catch (Exception e) {
            log.warn("Could not persist saved views to {}: {}", file, e.getMessage());
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            for (Map<String, Object> m : JSON.readValue(Files.readAllBytes(file), LIST)) {
                String name = String.valueOf(m.get("name"));
                @SuppressWarnings("unchecked")
                Map<String, String> filters = m.get("filters") instanceof Map<?, ?> raw
                        ? toStringMap(raw) : Map.of();
                long createdAt = m.get("createdAt") instanceof Number n ? n.longValue() : 0L;
                views.put(name, new SavedView(name, filters, createdAt));
            }
        } catch (Exception e) {
            log.warn("Could not load saved views from {}: {}", file, e.getMessage());
        }
    }

    private static Map<String, String> toStringMap(Map<?, ?> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> { if (k != null && v != null) out.put(k.toString(), v.toString()); });
        return out;
    }
}
