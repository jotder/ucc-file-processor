package com.gamma.service;

import java.util.regex.Pattern;

/**
 * A validated, filesystem-safe space identifier — the single enforcement point that jails a space's directory
 * under {@code spaces/}. Lower-case alphanumerics and hyphens only, 1–63 chars, not starting with a hyphen, so
 * an id can never contain {@code /}, {@code \}, {@code .}, or {@code ..} and therefore can never escape the
 * container root. {@code "default"} (the single-tenant / legacy space) is a valid id.
 */
public record SpaceId(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    public SpaceId {
        if (value == null || !VALID.matcher(value).matches())
            throw new IllegalArgumentException(
                    "Invalid space id '" + value + "' — expected [a-z0-9-], 1-63 chars, not starting with '-'");
    }

    public static SpaceId of(String value) {
        return new SpaceId(value);
    }

    /** Whether {@code value} is a valid space id (no throw) — for callers that want to reject early without catching. */
    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
