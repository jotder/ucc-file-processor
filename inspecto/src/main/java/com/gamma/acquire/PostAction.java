package com.gamma.acquire;

import java.util.Map;

/**
 * What to do with the <em>source-side</em> file after it has been processed successfully
 * (Data Acquisition roadmap §9 / Phase F). Validated against the connector's
 * {@link CollectorConnector.Capability capabilities} before being applied.
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
        /** Delete the source file (requires {@link CollectorConnector.Capability#DELETE}). */ DELETE,
        /** Move it to an archive location (requires {@code MOVE}). */ MOVE,
        /** Rename it in place, e.g. {@code processed_<name>} (requires {@code RENAME}). */ RENAME,
        /** Tag the object's metadata (object storage; requires {@code TAG}). */ TAG
    }

    /** A simple MOVE-to-archive action. */
    public static PostAction move(String archiveTemplate) {
        return new PostAction(Kind.MOVE, archiveTemplate, Map.of());
    }

    /**
     * Resolve date placeholders in an {@code archive_path} template against {@code when} so a configured
     * {@code archive/yyyy/MM/dd} lands in a dated sub-tree. Supported tokens (longest-first so {@code yyyy} is not
     * eaten by {@code yy}): {@code yyyy yy MM dd HH mm ss}. A {@code null}/blank template returns {@code null}.
     */
    public static String resolveTemplate(String template, java.time.ZonedDateTime when) {
        if (template == null || template.isBlank()) return null;
        return template
                .replace("yyyy", String.format("%04d", when.getYear()))
                .replace("yy",   String.format("%02d", when.getYear() % 100))
                .replace("MM",   String.format("%02d", when.getMonthValue()))
                .replace("dd",   String.format("%02d", when.getDayOfMonth()))
                .replace("HH",   String.format("%02d", when.getHour()))
                .replace("mm",   String.format("%02d", when.getMinute()))
                .replace("ss",   String.format("%02d", when.getSecond()));
    }
}
