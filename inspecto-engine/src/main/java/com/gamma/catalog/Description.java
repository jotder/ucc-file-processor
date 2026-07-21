package com.gamma.catalog;

import com.gamma.api.PublicApi;

/**
 * A description with its {@link Provenance}, used for column and table prose.
 *
 * <p>Descriptions arrive from three sources — manual authoring, AI suggestion, and heuristic
 * deduction — and must be merged without a higher-authority source ever losing to a lower one.
 * {@link #mergePreferring} encodes that rule, so the metadata graph can re-derive descriptions
 * on every rebuild while leaving operator-authored prose untouched.
 *
 * @param text       the description text (never {@code null}; blank means "no prose")
 * @param provenance where {@link #text} came from (never {@code null})
 */
@PublicApi(since = "4.0.0")
public record Description(String text, Provenance provenance) {

    /** The absence of a description. */
    public static final Description EMPTY = new Description("", Provenance.NONE);

    public Description {
        text = text == null ? "" : text;
        provenance = provenance == null ? Provenance.NONE : provenance;
    }

    /** A {@code MANUAL} description (operator-authored prose). */
    public static Description manual(String text) {
        return text == null || text.isBlank() ? EMPTY : new Description(text, Provenance.MANUAL);
    }

    /** Whether this carries actual prose. */
    public boolean isPresent() {
        return !text.isBlank();
    }

    /**
     * Combine with another candidate description, keeping the higher-ranked one.
     *
     * <p>Rank is the {@link Provenance#ordinal()} (lower = higher authority). When {@code other}
     * outranks this one it wins; on a tie (or a lower-ranked {@code other}, or {@code null}) the
     * incumbent is kept — authored prose is sticky.
     */
    public Description mergePreferring(Description other) {
        if (other == null) {
            return this;
        }
        return other.provenance.ordinal() < this.provenance.ordinal() ? other : this;
    }
}
