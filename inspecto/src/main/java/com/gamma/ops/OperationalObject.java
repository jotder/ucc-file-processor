package com.gamma.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One mutable operational object — Layer 2 of the Operational Intelligence Platform
 * ({@code docs/superpowers/specs/2026-06-13-operational-intelligence-roadmap.md}). Where an
 * {@link com.gamma.event.Event} is an immutable fact ("what happened"), an {@code OperationalObject}
 * is a managed thing that <b>changes state</b> ("should I care / am I handling it"): its
 * {@link #status()} walks a {@link com.gamma.ops.workflow.Workflow} (e.g. an {@link ObjectType#ALERT}
 * goes {@code OPEN → ACKNOWLEDGED → RESOLVED}). Because the row mutates it lives in a table store
 * ({@link ObjectStore}), not append-only Parquet.
 *
 * <h3>Shape</h3>
 * The columns mirror the requirement's object model: {@code id, object_type, title, description,
 * status, severity, priority, owner, assignee, created_at, updated_at, closed_at}, plus a
 * {@link #correlationId()} tying the object to the event/batch that spawned it and an extensible
 * {@link #attributes()} bag (e.g. an alert carries {@code rule}/{@code metric}/{@code value}).
 *
 * <p>The record itself is immutable; a lifecycle change produces a new instance via {@link #withStatus}
 * / {@link #withAssignee}, and {@link ObjectStore#update} persists it.
 *
 * @since 4.3.0
 */
@com.gamma.api.PublicApi(since = "4.3.0")
public record OperationalObject(String id, ObjectType objectType, String title, String description,
                                String status, String severity, String priority, String owner,
                                String assignee, String correlationId, Map<String, String> attributes,
                                long createdAt, long updatedAt, long closedAt) {

    /** Canonical constructor — validates the keys, defaults text fields, makes {@code attributes} immutable. */
    public OperationalObject {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("object id is required");
        if (objectType == null) throw new IllegalArgumentException("objectType is required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }

    /** A copy in a new {@code status}; {@code now} updates {@code updatedAt}, and {@code closedAt} when terminal. */
    public OperationalObject withStatus(String newStatus, long now, boolean terminal) {
        return new OperationalObject(id, objectType, title, description, newStatus, severity, priority,
                owner, assignee, correlationId, attributes, createdAt, now, terminal ? now : closedAt);
    }

    /** A copy reassigned to {@code newAssignee} (touches {@code updatedAt}). */
    public OperationalObject withAssignee(String newAssignee, long now) {
        return new OperationalObject(id, objectType, title, description, status, severity, priority,
                owner, newAssignee, correlationId, attributes, createdAt, now, closedAt);
    }

    /** A copy at a new {@code severity} (INC-4 escalation bump); touches {@code updatedAt}. */
    public OperationalObject withSeverity(String newSeverity, long now) {
        return new OperationalObject(id, objectType, title, description, status, newSeverity, priority,
                owner, assignee, correlationId, attributes, createdAt, now, closedAt);
    }

    /**
     * The object's watcher list (INC-4) — the comma-separated {@code watchers} attribute parsed into a list,
     * or empty. Watchers are subscribers notified when the object changes; they ride the attribute bag so
     * they persist with the object across either store backend.
     */
    public java.util.List<String> watchers() {
        String raw = attributes.get("watchers");
        if (raw == null || raw.isBlank()) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : raw.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    /**
     * A copy with {@code updates} merged over the current {@link #attributes()} (updates win; null keys
     * or values are skipped); touches {@code updatedAt}. Used to stamp derived markers such as the
     * Phase-3 {@code slaBreachedAt} without disturbing status or assignment.
     */
    public OperationalObject withAttributes(Map<String, String> updates, long now) {
        Map<String, String> merged = new LinkedHashMap<>(attributes);
        if (updates != null) updates.forEach((k, v) -> { if (k != null && v != null) merged.put(k, v); });
        return new OperationalObject(id, objectType, title, description, status, severity, priority,
                owner, assignee, correlationId, merged, createdAt, now, closedAt);
    }

    /** {@code true} once {@link #closedAt()} is set (the object reached a terminal state). */
    public boolean isClosed() {
        return closedAt > 0;
    }

    /** JSON-ready view (stable key order) — backs the {@code /objects} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("objectType", objectType.name());
        m.put("title", title);
        m.put("description", description);
        m.put("status", status);
        m.put("severity", severity);
        m.put("priority", priority);
        m.put("owner", owner);
        m.put("assignee", assignee);
        m.put("correlationId", correlationId);
        m.put("attributes", attributes);
        m.put("watchers", watchers());
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        m.put("closedAt", closedAt);
        return m;
    }

    /** Start a builder for a new object of {@code type}; {@code id} auto-generates and timestamps default to now. */
    public static Builder builder(ObjectType type) {
        return new Builder(type);
    }

    /** Fluent builder — {@code type} and {@code status} are required; the rest are optional. */
    public static final class Builder {
        private final ObjectType objectType;
        private String id = null;
        private String title = "";
        private String description = "";
        private String status;
        private String severity;
        private String priority;
        private String owner;
        private String assignee;
        private String correlationId;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = createdAt;
        private long closedAt = 0;

        private Builder(ObjectType type) { this.objectType = type; }

        public Builder id(String id) { this.id = id; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder severity(String severity) { this.severity = severity; return this; }
        public Builder priority(String priority) { this.priority = priority; return this; }
        public Builder owner(String owner) { this.owner = owner; return this; }
        public Builder assignee(String assignee) { this.assignee = assignee; return this; }
        public Builder correlationId(String id) { this.correlationId = id; return this; }
        public Builder createdAt(long ms) { this.createdAt = ms; return this; }
        public Builder updatedAt(long ms) { this.updatedAt = ms; return this; }

        /** Add one attribute; {@code null} key or value is ignored. */
        public Builder attr(String key, Object value) {
            if (key != null && value != null) attributes.put(key, String.valueOf(value));
            return this;
        }

        /** Add all of {@code attrs} (skips null values). */
        public Builder attributes(Map<String, String> attrs) {
            if (attrs != null) attrs.forEach(this::attr);
            return this;
        }

        public OperationalObject build() {
            String oid = (id == null || id.isBlank())
                    ? objectType.name() + "-" + UUID.randomUUID()
                    : id;
            return new OperationalObject(oid, objectType, title, description, status, severity, priority,
                    owner, assignee, correlationId, attributes, createdAt, updatedAt, closedAt);
        }
    }
}
