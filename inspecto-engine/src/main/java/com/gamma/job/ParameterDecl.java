package com.gamma.job;

/**
 * A Job Type's declaration of one runtime Parameter it needs (R3, §7.1): its {@code name} and
 * {@link ParamType}, whether it is {@code required}, an optional {@code deduce} {@code $}-expression
 * tried when nothing explicit is bound, a literal {@code defaultValue} fallback, and a description.
 * Surfaced verbatim by {@code GET /jobs/types/{id}} so a UI can render an authoring form.
 *
 * <p>Consumed at run time by {@link ParameterResolver} (P3a): {@code deduce} is a {@code $}-expression
 * tried when nothing explicit is bound, {@code defaultValue} the literal fallback after it. A missing
 * {@code required} parameter fails the Run REJECTED before user code runs (§7.2).
 */
public record ParameterDecl(String name, ParamType type, boolean required,
                            String deduce, String defaultValue, String description) {

    public static ParameterDecl required(String name, ParamType type, String description) {
        return new ParameterDecl(name, type, true, null, null, description);
    }

    public static ParameterDecl optional(String name, ParamType type, String defaultValue, String description) {
        return new ParameterDecl(name, type, false, null, defaultValue, description);
    }
}
