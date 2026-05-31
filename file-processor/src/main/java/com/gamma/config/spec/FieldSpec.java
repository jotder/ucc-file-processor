package com.gamma.config.spec;

import java.util.List;

/**
 * The declarative description of one configuration field — the single source of truth that drives
 * in-memory validation, structured API output, LLM grammar-constrained generation, and generic UI
 * form rendering.
 *
 * <p>Today these facts are implicit: scattered across {@code getOrDefault} calls, eager
 * {@code require} throws, and {@code .toUpperCase()} normalisations inside the various {@code load}
 * methods. {@code FieldSpec} lifts them into data so a caller that has never seen the Java code can
 * still know a field's type, whether it is required, its default, and its allowed values.
 *
 * @param path         dotted path into the decoded config map (e.g. {@code "processing.threads"})
 * @param label        short human label for a form control (never {@code null})
 * @param description  longer prose explaining the field (never {@code null}; may be blank)
 * @param type         the value shape (drives type-checking + form-control selection)
 * @param required     whether validation emits an ERROR when the field is absent/blank
 * @param defaultValue the value applied when absent ({@code null} = no default)
 * @param enumValues   allowed values when {@link #type} is {@link FieldType#ENUM} (never {@code null})
 * @param pattern      optional regex the value must fully match ({@code null} = unconstrained)
 * @param uiHint       optional rendering hint (e.g. {@code "select"}, {@code "cron-editor"})
 * @param visibleWhen  optional {@code "otherPath=value"} UI predicate for conditional display
 *                     (a rendering hint only — never evaluated server-side)
 */
public record FieldSpec(String path, String label, String description, FieldType type,
                        boolean required, Object defaultValue, List<String> enumValues,
                        String pattern, String uiHint, String visibleWhen) {

    public FieldSpec {
        path = path == null ? "" : path;
        label = label == null ? "" : label;
        description = description == null ? "" : description;
        type = type == null ? FieldType.STRING : type;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    // ── concise builders for the common shapes (keeps ConfigSpecs readable) ──────

    /** An optional field of {@code type} with a description. */
    public static FieldSpec of(String path, String label, FieldType type, String description) {
        return new FieldSpec(path, label, description, type, false, null, List.of(), null, null, null);
    }

    /** A required field of {@code type}. */
    public static FieldSpec required(String path, String label, FieldType type, String description) {
        return new FieldSpec(path, label, description, type, true, null, List.of(), null, null, null);
    }

    /** An optional field with a default value. */
    public static FieldSpec withDefault(String path, String label, FieldType type,
                                        Object defaultValue, String description) {
        return new FieldSpec(path, label, description, type, false, defaultValue, List.of(), null, null, null);
    }

    /** An optional ENUM field with its allowed values and a default. */
    public static FieldSpec enumField(String path, String label, List<String> values,
                                      Object defaultValue, String description) {
        return new FieldSpec(path, label, description, FieldType.ENUM, false, defaultValue,
                values, null, "select", null);
    }
}
