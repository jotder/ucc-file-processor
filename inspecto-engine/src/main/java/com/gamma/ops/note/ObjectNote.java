package com.gamma.ops.note;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One append-only annotation on an operational object — the "Evidence/Notes/Attachments" of the
 * Operational Intelligence Platform (Phase 4 follow-up). A {@link NoteKind#COMMENT} carries free text
 * in {@link #body()}; a {@link NoteKind#ATTACHMENT} references external evidence — the file/URL
 * metadata ({@code name}/{@code contentType}/{@code uri}) rides the extensible {@link #attributes()}
 * bag (the same idiom as {@link com.gamma.ops.OperationalObject}), so one table serves both kinds and
 * the <b>bytes never enter the lean core</b> — only a reference does.
 *
 * <p>Like an {@link com.gamma.event.Event} and an {@link com.gamma.ops.link.ObjectLink}, a note is an
 * immutable, append-only fact: created and read, never mutated.
 *
 * @since 4.6.0
 */
@com.gamma.api.PublicApi(since = "4.6.0")
public record ObjectNote(String id, String objectId, NoteKind kind, String author, String body,
                         Map<String, String> attributes, long createdAt) {

    /** Canonical constructor — validates keys, defaults text, makes {@code attributes} immutable. */
    public ObjectNote {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("note id is required");
        if (objectId == null || objectId.isBlank()) throw new IllegalArgumentException("note objectId is required");
        if (kind == null) throw new IllegalArgumentException("note kind is required");
        author = author == null ? "" : author;
        body = body == null ? "" : body;
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }

    /** A free-text {@link NoteKind#COMMENT} on {@code objectId}, stamped now. */
    public static ObjectNote comment(String objectId, String author, String body) {
        return new ObjectNote(newId(), objectId, NoteKind.COMMENT, author, body, Map.of(),
                System.currentTimeMillis());
    }

    /**
     * An {@link NoteKind#ATTACHMENT} on {@code objectId} referencing external evidence; {@code body} is
     * an optional caption, and {@code name}/{@code contentType}/{@code uri} are stored as attributes.
     */
    public static ObjectNote attachment(String objectId, String author, String name, String contentType,
                                        String uri, String caption) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (name != null) attrs.put("name", name);
        if (contentType != null) attrs.put("contentType", contentType);
        if (uri != null) attrs.put("uri", uri);
        return new ObjectNote(newId(), objectId, NoteKind.ATTACHMENT, author, caption == null ? "" : caption,
                attrs, System.currentTimeMillis());
    }

    /** JSON-ready view (stable key order) — backs the {@code /objects/{id}/comments|attachments} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("objectId", objectId);
        m.put("kind", kind.name());
        m.put("author", author);
        m.put("body", body);
        m.put("attributes", attributes);
        m.put("createdAt", createdAt);
        return m;
    }

    private static String newId() {
        return "NOTE-" + UUID.randomUUID();
    }
}
