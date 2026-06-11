package com.gamma.config.io;

import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.CrossFieldRule;
import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.RawConfig;
import com.gamma.config.spec.Severity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pure, dependency-injected orchestration of config loading: <b>decode → validate</b>.
 *
 * <p>It holds a {@link ResourceLoader} so the same code validates a file on disk and an unsaved
 * draft from a REST body — the only difference is which loader is injected. Neither method has any
 * side effect (no directory creation, no typed-{@code Config} instantiation): {@link #decode} just
 * parses text to a map, and {@link #validate} is a pure function of {@code (spec, map)}. That makes
 * "what's wrong with this draft?" answerable without ever writing a file.
 *
 * <p>Validation reproduces, declaratively, the checks that today live as eager throws inside the
 * various {@code load} methods and as messages in {@code ConfigValidator}: per-field
 * required/type/enum/pattern, then every {@link CrossFieldRule} in the spec.
 */
public final class ConfigLoader {

    private final ResourceLoader resourceLoader;

    public ConfigLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader == null ? ResourceLoader.filesystem() : resourceLoader;
    }

    /** A loader backed by the filesystem (the production default). */
    public static ConfigLoader filesystem() {
        return new ConfigLoader(ResourceLoader.filesystem());
    }

    // ── decode (no side effects) ─────────────────────────────────────────────────

    /** Load and decode {@code ref} to a raw map (lenient — tolerates on-disk comments). */
    public Map<String, Object> decode(String ref) throws IOException {
        return ConfigCodec.toMap(resourceLoader.load(ref));
    }

    // ── validate (pure) ──────────────────────────────────────────────────────────

    /**
     * Validate a decoded config map against {@code spec}: per-field rules first, then cross-field
     * rules. Returns every finding (ERROR + WARNING) in declaration order; an empty list means the
     * draft is clean.
     */
    public List<Finding> validate(ConfigSpec spec, Map<String, Object> raw) {
        List<Finding> findings = new ArrayList<>();
        if (spec == null || raw == null) {
            return findings;
        }
        for (FieldSpec f : spec.fields()) {
            validateField(f, raw, findings);
        }
        for (CrossFieldRule rule : spec.rules()) {
            rule.check(raw).ifPresent(findings::add);
        }
        return findings;
    }

    /** Convenience: decode {@code ref} then validate against {@code spec}. */
    public List<Finding> decodeAndValidate(ConfigSpec spec, String ref) throws IOException {
        return validate(spec, decode(ref));
    }

    // ── per-field checks ──────────────────────────────────────────────────────────

    private void validateField(FieldSpec f, Map<String, Object> raw, List<Finding> out) {
        boolean present = RawConfig.present(raw, f.path());
        if (!present) {
            if (f.required()) {
                out.add(Finding.error(f.path(), "Missing required field '" + f.path() + "'"));
            }
            return; // absent-and-optional: nothing more to check (default applies at parse time)
        }
        Object value = RawConfig.at(raw, f.path());
        switch (f.type()) {
            case INT -> requireParsable(f, value, out, true);
            case LONG -> requireParsable(f, value, out, false);
            case BOOL -> {
                String s = value.toString().trim();
                if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) {
                    out.add(Finding.error(f.path(), "Field '" + f.path() + "' must be true/false, got: " + s));
                }
            }
            case ENUM -> {
                String s = value.toString().trim();
                boolean ok = f.enumValues().stream().anyMatch(v -> v.equalsIgnoreCase(s));
                if (!ok) {
                    out.add(Finding.error(f.path(), "Field '" + f.path() + "' must be one of "
                            + f.enumValues() + ", got: " + s));
                }
            }
            case MAP -> {
                if (!(value instanceof Map<?, ?>)) {
                    out.add(Finding.error(f.path(), "Field '" + f.path() + "' must be a map/object"));
                }
            }
            case LIST -> {
                if (!(value instanceof List<?>) && !(value instanceof String)) {
                    out.add(Finding.error(f.path(), "Field '" + f.path() + "' must be a list"));
                }
            }
            default -> { /* STRING / FILEPATH / CRON / SQL — free text at field level */ }
        }
        if (f.pattern() != null && !f.pattern().isBlank()
                && !value.toString().matches(f.pattern())) {
            out.add(Finding.error(f.path(), "Field '" + f.path()
                    + "' does not match pattern " + f.pattern()));
        }
    }

    private void requireParsable(FieldSpec f, Object value, List<Finding> out, boolean intNotLong) {
        String s = value.toString().trim();
        try {
            if (intNotLong) {
                Integer.parseInt(s);
            } else {
                Long.parseLong(s);
            }
        } catch (NumberFormatException e) {
            String t = intNotLong ? "an integer" : "a long";
            out.add(new Finding(Severity.ERROR, f.path(),
                    "Field '" + f.path() + "' must be " + t + ", got: " + s));
        }
    }
}
