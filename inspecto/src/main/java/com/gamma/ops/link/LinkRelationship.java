package com.gamma.ops.link;

/**
 * Well-known {@link ObjectLink#relationship()} constants for the Phase-4 correlation graph. Like
 * {@link com.gamma.event.EventType}, the relationship is a free-form {@code String} (the model is
 * extensible) — these are conventions rather than a closed enum, so a caller may coin a new
 * relationship without touching this class. Values are normalised to upper-case by {@link ObjectLink}.
 *
 * <p>The vocabulary mirrors the requirement's examples: a {@code CASE} {@link #CONTAINS} the
 * {@code INCIDENT}s it groups; an {@code INCIDENT} is {@link #ESCALATED_FROM} the {@code ALERT} that raised
 * it; an {@code ALERT} is {@link #CAUSED_BY} the originating event (lightweight today via
 * {@code correlationId}). {@link #RELATED_TO} is the generic fallback.
 *
 * @since 4.5.0
 */
@com.gamma.api.PublicApi(since = "4.5.0")
public final class LinkRelationship {

    private LinkRelationship() {}

    /** A container/parent relationship — e.g. {@code Case CONTAINS Incident}. */
    public static final String CONTAINS = "CONTAINS";
    /** A promotion relationship — e.g. {@code Incident ESCALATED_FROM Alert}. */
    public static final String ESCALATED_FROM = "ESCALATED_FROM";
    /** A causation relationship — e.g. {@code Alert CAUSED_BY Event}. */
    public static final String CAUSED_BY = "CAUSED_BY";
    /** Generic correlation when no stronger relationship applies. */
    public static final String RELATED_TO = "RELATED_TO";
    /** Case group management (GLOSSARY §9): an absorbed {@code Case MERGED_INTO} the surviving Case. */
    public static final String MERGED_INTO = "MERGED_INTO";
    /** Case group management (GLOSSARY §9): a carved-out {@code Case SPLIT_FROM} its original Case. */
    public static final String SPLIT_FROM = "SPLIT_FROM";
}
