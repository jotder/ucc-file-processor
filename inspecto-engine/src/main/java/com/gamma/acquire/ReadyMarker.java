package com.gamma.acquire;

/**
 * The ready-marker (Phase-B sibling sentinel) convention shared by every file connector. A
 * {@code ready_marker} template names a per-file sentinel that a producer writes once a data file is
 * fully landed, so discovery only picks up files whose marker is present (and never ingests the marker
 * itself). The template's {@code "{name}"} placeholder expands to the data file's name; a template with
 * no {@code "{name}"} is treated as a suffix appended to the name.
 */
public final class ReadyMarker {

    private static final String NAME = "{name}";

    private ReadyMarker() {}

    /**
     * Whether {@code name} is itself a marker file for the given {@code marker} template — so discovery
     * skips it rather than ingesting it. A {@code null} template means no marker convention (never matches).
     */
    public static boolean matches(String marker, String name) {
        if (marker == null) return false;
        int i = marker.indexOf(NAME);
        String prefix = i < 0 ? "" : marker.substring(0, i);
        String suffix = i < 0 ? marker : marker.substring(i + NAME.length());
        return name.length() > prefix.length() + suffix.length()
                && name.startsWith(prefix) && name.endsWith(suffix);
    }

    /** Expand the {@code marker} template for one data file: {@code "{name}"} → {@code name}, else suffix it. */
    public static String apply(String marker, String name) {
        return marker.contains(NAME) ? marker.replace(NAME, name) : name + marker;
    }
}
