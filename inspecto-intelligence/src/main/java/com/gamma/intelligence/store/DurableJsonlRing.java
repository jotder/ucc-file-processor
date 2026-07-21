package com.gamma.intelligence.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A bounded, durable ring of {@code T} persisted as JSON lines (AGT-5). Extracted (M7) from the four
 * intelligence stores that hand-rolled the identical mechanics — {@code ApprovalStore}, {@code CaseStore},
 * {@code FeedbackStore}, {@code RunbookRunStore} — each of which now subclasses this and adds only its own
 * domain queries.
 *
 * <p>With a {@code file}, the ring is durable: loaded at construction and rewritten (one JSON object per
 * line, ≤ capacity lines) on every mutation, so its contents survive a process restart. Without a file
 * (a {@code null} path) it is in-memory only — the dev/test behaviour. All persistence failures degrade
 * to in-memory (log + continue); durability never blocks a caller.
 *
 * <p>Every method holds this store's monitor; subclasses layering queries over {@link #ring} must be
 * {@code synchronized} on the same instance (they inherit the same lock).
 */
public abstract class DurableJsonlRing<T> {

    /** Maps a payload to/from its persisted JSON-object shape. Lives in the subclass's package so it may
     *  reach the payload's package-private {@code toRecord}/{@code fromRecord}. */
    public interface Codec<T> {
        Map<String, Object> toRecord(T item);
        T fromRecord(Map<String, Object> record);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final int capacity;
    private final Path file; // null → in-memory only
    private final Codec<T> codec;
    private final String noun; // for the load/persist log lines, e.g. "approval(s)"
    protected final Deque<T> ring = new ArrayDeque<>();

    protected DurableJsonlRing(int capacity, Path file, Codec<T> codec, String noun) {
        this.capacity = capacity;
        this.file = file;
        this.codec = codec;
        this.noun = noun;
        load();
    }

    /** Append an item, evicting the oldest when at capacity, then persist. */
    protected synchronized void append(T item) {
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(item);
        persist();
    }

    /** Newest-first snapshot, capped at {@code limit}. */
    protected synchronized List<T> recentSnapshot(int limit) {
        List<T> copy = new ArrayList<>(ring);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(limit, copy.size()));
    }

    public synchronized int size() { return ring.size(); }

    /** Rewrite the whole file from the ring — call after any in-place mutation of a held item. */
    protected synchronized void persist() {
        if (file == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (T item : ring) sb.append(JSON.writeValueAsString(codec.toRecord(item))).append('\n');
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not persist {} to {}: {}", noun, file, e.getMessage());
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Map<String, Object> record = JSON.readValue(line, new TypeReference<Map<String, Object>>() {});
                if (ring.size() >= capacity) ring.removeFirst();
                ring.addLast(codec.fromRecord(record));
            }
            log.info("Loaded {} persisted {} from {}", ring.size(), noun, file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load persisted {} from {}: {}", noun, file, e.getMessage());
            ring.clear();
        }
    }
}
