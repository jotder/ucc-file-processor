package com.gamma.acquire;

import java.util.Map;

/**
 * What to do with the <em>source-side</em> file after it has been processed successfully
 * (Data Acquisition roadmap §9 / Phase F). Validated against the connector's
 * {@link SourceConnector.Capability capabilities} before being applied.
 *
 * <p>{@code archiveTemplate} is used by {@link Kind#MOVE} (e.g. {@code archive/yyyy/MM/dd}) and
 * {@code tags} by {@link Kind#TAG}; both are ignored by the other kinds.
 */
public record PostAction(Kind kind, String archiveTemplate, Map<String, String> tags) {

    /** Leave the source untouched (the legacy behaviour, and the default). */
    public static final PostAction RETAIN = new PostAction(Kind.RETAIN, null, Map.of());

    public PostAction {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public enum Kind {
        /** Leave the source file in place. */                         RETAIN,
        /** Delete the source file (requires {@link SourceConnector.Capability#DELETE}). */ DELETE,
        /** Move it to an archive location (requires {@code MOVE}). */ MOVE,
        /** Rename it in place, e.g. {@code processed_<name>} (requires {@code RENAME}). */ RENAME,
        /** Tag the object's metadata (object storage; requires {@code TAG}). */ TAG
    }

    /** A simple MOVE-to-archive action. */
    public static PostAction move(String archiveTemplate) {
        return new PostAction(Kind.MOVE, archiveTemplate, Map.of());
    }
}
