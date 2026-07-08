package com.gamma.exchange;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A cross-Space reference of the form {@code shared/<owner>/<item>} — the provenance-readable name a
 * consumer Space uses to point at a Dataset (or Widget) another Space has offered through the
 * {@link Exchange}. Provenance rides in the name (no shadowing), and the ref stays a plain string
 * everywhere refs already flow (Widget bindings, Alert Rule {@code dataset:}, job params).
 *
 * <p>Resolution is grant-checked and fail-closed elsewhere ({@link Exchange#resolveForConsumer}); this
 * type only parses/validates the <em>shape</em>. {@code owner} matches a {@code SpaceId}
 * ({@code [a-z0-9-]}); {@code item} matches a component id ({@code [A-Za-z0-9._-]}).
 */
public record SharedRef(String owner, String item) {

    /** The reserved prefix that marks a ref as living in the Exchange rather than the calling Space. */
    public static final String PREFIX = "shared/";

    private static final Pattern OWNER = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern ITEM  = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** True when {@code ref} carries the {@link #PREFIX} (a candidate cross-Space ref, shape unchecked). */
    public static boolean isShared(String ref) {
        return ref != null && ref.startsWith(PREFIX);
    }

    /**
     * Parse {@code shared/<owner>/<item>} into its parts, or empty when the string is not a
     * well-formed shared ref (wrong prefix, wrong segment count, or a segment failing its charset).
     */
    public static Optional<SharedRef> parse(String ref) {
        if (!isShared(ref)) return Optional.empty();
        String[] parts = ref.substring(PREFIX.length()).split("/", -1);
        if (parts.length != 2) return Optional.empty();
        String owner = parts[0], item = parts[1];
        if (!OWNER.matcher(owner).matches() || item.contains("..") || !ITEM.matcher(item).matches())
            return Optional.empty();
        return Optional.of(new SharedRef(owner, item));
    }

    /** The canonical string form {@code shared/<owner>/<item>}. */
    public String ref() {
        return PREFIX + owner + "/" + item;
    }
}
