package com.gamma.ops.queue;

import com.gamma.config.io.ConfigCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A first-class work queue (INC-4) — a named bucket of {@link #members()} that
 * {@link com.gamma.ops.OperationalObject}s are routed into, so incidents are assigned to a <em>team</em>
 * rather than only a single person. When an object is assigned to a queue, {@link QueueRouter} picks the
 * concrete member per the queue's {@link Routing} strategy.
 *
 * <p>Authored as a {@code *_queue.toon} (a {@code queue { … }} block) and loaded into a by-id registry on
 * the {@link com.gamma.ops.ObjectService} at bootstrap, exactly like {@code *_rca.toon} templates; may also
 * be created/updated at runtime via {@code POST /queues}.
 *
 * @since 4.9.0
 */
@com.gamma.api.PublicApi(since = "4.9.0")
public record Queue(String id, String name, String description, List<String> members, Routing routing) {

    /** How {@link QueueRouter} chooses a member when an object is assigned to the queue. */
    public enum Routing {
        /** Cycle through members in order (a persistent per-queue cursor). */          ROUND_ROBIN,
        /** Pick the member with the fewest open objects currently assigned. */         LEAST_LOADED,
        /** No auto-pick — assignment to this queue requires an explicit assignee. */   MANUAL;

        /** Parse a config token ({@code round_robin}|{@code least_loaded}|{@code manual}); default ROUND_ROBIN. */
        public static Routing from(String s) {
            if (s == null || s.isBlank()) return ROUND_ROBIN;
            try {
                return Routing.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("queue routing must be round_robin|least_loaded|manual, not '" + s + "'");
            }
        }
    }

    public Queue {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("queue id is required");
        id = id.trim();
        name = (name == null || name.isBlank()) ? id : name.trim();
        description = description == null ? "" : description;
        members = members == null ? List.of() : List.copyOf(members);
        if (routing == null) routing = Routing.ROUND_ROBIN;
    }

    /** JSON-ready view (stable key order) — backs the {@code /queues} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("members", members);
        m.put("routing", routing.name().toLowerCase(Locale.ROOT));
        return m;
    }

    /** Load a {@code *_queue.toon} (a {@code queue { … }} block). */
    @SuppressWarnings("unchecked")
    public static Queue load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object q = root.get("queue");
        if (!(q instanceof Map)) throw new IllegalArgumentException(path + " has no 'queue' block");
        return fromMap((Map<String, Object>) q);
    }

    /** Parse + validate from a decoded {@code queue { … }} map. */
    public static Queue fromMap(Map<String, Object> q) {
        if (q == null) throw new IllegalArgumentException("missing 'queue' block");
        List<String> members = new ArrayList<>();
        if (q.get("members") instanceof List<?> list)
            for (Object o : list) { String s = str(o); if (s != null) members.add(s); }
        return new Queue(str(q.get("id")), str(q.get("name")), str(q.get("description")),
                members, Routing.from(str(q.get("routing"))));
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
