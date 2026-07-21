package com.gamma.ops.note;

import java.util.Locale;

/**
 * The kind of {@link ObjectNote} — the "Evidence/Notes/Attachments" layer of the Operational
 * Intelligence Platform (Phase 4 follow-up). One append-only notes table serves both kinds, keyed by
 * this discriminator (mirroring how one object table serves every {@link com.gamma.ops.ObjectType}).
 *
 * @since 4.6.0
 */
@com.gamma.api.PublicApi(since = "4.6.0")
public enum NoteKind {
    /** A free-text note/comment on an object (an investigation note, a status update). */
    COMMENT,
    /** A reference to external evidence (a file/URL) — metadata only; the bytes live elsewhere. */
    ATTACHMENT;

    /** Parse case-insensitively; {@code null}/blank → {@link #COMMENT} (the default kind). */
    public static NoteKind of(String s) {
        if (s == null || s.isBlank()) return COMMENT;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown note kind '" + s + "' (expected COMMENT or ATTACHMENT)");
        }
    }
}
