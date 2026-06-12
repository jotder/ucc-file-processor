package com.gamma.config.spec;

/**
 * One validation result — the structured unit returned by config validation.
 *
 * <p>Replaces the bare {@code List<String>} that {@code ConfigValidator} returns today: a UI can
 * anchor a {@code WARNING} to a specific form field via {@link #fieldPath}, and an LLM can read the
 * {@link #message} to self-correct a draft. {@code fieldPath} is a dotted path into the raw config
 * map (e.g. {@code "processing.threads"}); it is blank for findings that span fields (cross-field
 * rules may name the rule's primary field, or leave it blank).
 *
 * @param severity  ERROR (cannot run) or WARNING (suspicious but legal)
 * @param fieldPath dotted path the finding is anchored to (never {@code null}; may be blank)
 * @param message   human-readable explanation (never {@code null})
 */
public record Finding(Severity severity, String fieldPath, String message) {

    public Finding {
        severity = severity == null ? Severity.ERROR : severity;
        fieldPath = fieldPath == null ? "" : fieldPath;
        message = message == null ? "" : message;
    }

    /** An ERROR finding anchored to {@code fieldPath}. */
    public static Finding error(String fieldPath, String message) {
        return new Finding(Severity.ERROR, fieldPath, message);
    }

    /** A WARNING finding anchored to {@code fieldPath}. */
    public static Finding warning(String fieldPath, String message) {
        return new Finding(Severity.WARNING, fieldPath, message);
    }
}
