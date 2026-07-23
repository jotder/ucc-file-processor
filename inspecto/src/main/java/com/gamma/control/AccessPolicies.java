package com.gamma.control;

import com.gamma.api.PublicApi;
import com.gamma.util.AtomicFiles;
import com.gamma.util.Conditions;
import com.gamma.util.ToonHelper;
import com.sun.net.httpserver.HttpExchange;
import dev.toonformat.jtoon.JToon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access Policies — authorable allow/deny statements over subject/resource/environment attributes
 * (ABAC A2, {@code docs/superpower/rbac-abac-plan.md} §4; vocabulary {@code docs/GLOSSARY.md} §1-A —
 * ⛔ never bare "Rule"). Authored as a per-space settings doc {@value #FILE} (same discipline as
 * {@code roles.toon}): each policy is {@code {name, effect: allow|deny, target: {actions?,
 * resourceKinds?}, when?}} where {@code when} is a {@link Conditions} expression over
 * {@code subject.* / resource.* / env.*} attribute maps.
 *
 * <p>The core stays auth-free: this class only stores, validates, and serves the documents
 * ({@code AccessRoutes} authors them, any edition can read them); <em>evaluation and enforcement</em>
 * are the Enterprise policy engine's job (A3's {@code PolicyEngine implements AccessDecider} in
 * {@code inspecto-policy}) — on Personal/Standard classpaths a stored policy has no runtime effect.
 *
 * <p><b>Fail-closed:</b> conditions are parsed at load time, so a doc that no longer parses — TOON
 * damage or a condition edit that breaks the grammar — marks the whole doc unreadable; the engine
 * must treat that as deny-loudly, never as "no policies" (mirrors {@link Roles}' suspended grants).
 * Authoring-time violations are 422s, so an unparseable doc can only arise from on-disk edits.
 */
@PublicApi(since = "5.0.0")
public final class AccessPolicies {
    private AccessPolicies() {}

    private static final Logger LOG = LoggerFactory.getLogger(AccessPolicies.class);

    static final String FILE = "access-policies.toon";

    /** The environment action verbs a policy target may name (plan §4 A1). */
    public static final Set<String> ACTIONS = Set.of("read", "write", "operate");

    private static final Set<String> EFFECTS = Set.of("allow", "deny");
    private static final int MAX_POLICIES = 200;
    private static final int MAX_TARGET_VALUES = 64;
    private static final int MAX_CONDITION_LENGTH = 2000;

    /**
     * One parsed policy. {@code actions}/{@code resourceKinds} empty ⇒ the target dimension is
     * unconstrained (matches every action / every resource kind); {@code when} blank ⇒ the policy
     * applies whenever the target matches ({@link #condition} is constant-true then). The
     * {@link #condition} is pre-parsed — evaluation never re-parses.
     */
    public record Policy(String name, String effect, Set<String> actions, Set<String> resourceKinds,
                         String when, Conditions.Condition condition) {
        public Policy {
            actions = Set.copyOf(actions);
            resourceKinds = Set.copyOf(resourceKinds);
        }

        public boolean deny() {
            return "deny".equals(effect);
        }
    }

    /** The authored doc + its readability (unreadable ⇒ the engine denies loudly, never skips). */
    public record Doc(List<Policy> policies, boolean unreadable) {
        static final Doc ABSENT = new Doc(List.of(), false);

        public Doc {
            policies = List.copyOf(policies);
        }
    }

    private record Cached(long mtime, long size, Doc doc) {}

    private static final ConcurrentHashMap<Path, Cached> CACHE = new ConcurrentHashMap<>();

    // ── resolution (the A3 engine's read seam) ──────────────────────────────────────

    /** Per-request policies for the bound space (via {@link Roles#ATTR_CONFIG_ROOT}, stamped by
     *  {@code ControlApi} pre-auth). Never null; empty doc when no root is bound. */
    public static Doc effective(HttpExchange ex) {
        return load(ex.getAttribute(Roles.ATTR_CONFIG_ROOT) instanceof Path p ? p : null);
    }

    /** The authored doc at {@code configRoot} (mtime/size-cached — an on-disk edit or an
     *  {@code AccessRoutes} PUT is picked up on the next read, no restart). */
    public static Doc load(Path configRoot) {
        if (configRoot == null) return Doc.ABSENT;
        Path file = configRoot.resolve(FILE);
        if (!Files.exists(file)) return Doc.ABSENT;
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            Cached hit = CACHE.get(file);
            if (hit != null && hit.mtime() == mtime && hit.size() == size) return hit.doc();
            Doc parsed = parseFile(file);
            CACHE.put(file, new Cached(mtime, size, parsed));
            return parsed;
        } catch (IOException e) {
            LOG.warn("access-policies: cannot stat {} — marking unreadable (deny loudly): {}", file, e.toString());
            return new Doc(List.of(), true);
        }
    }

    private static Doc parseFile(Path file) {
        try {
            Map<String, Object> m = ToonHelper.load(file.toString());
            return new Doc(validate(m.get("policies")), false);
        } catch (Exception e) {
            LOG.warn("access-policies: {} is unreadable — the policy engine must DENY (fail-closed) until fixed: {}",
                    file, e.toString());
            return new Doc(List.of(), true);
        }
    }

    // ── validation (shared by the PUT route and the file parser — one grammar) ──────

    /** Parse+validate a {@code policies} list (wire {@code resourceKinds} and on-disk
     *  {@code resource_kinds} both accepted). Throws {@link ApiException} 422 on any violation —
     *  including a {@code when} that does not parse ({@link Conditions} is the authoring gate). */
    static List<Policy> validate(Object policiesObj) {
        if (!(policiesObj instanceof List<?> raw))
            throw new ApiException(422, "access policies require a 'policies' list");
        if (raw.size() > MAX_POLICIES)
            throw new ApiException(422, "too many policies (max " + MAX_POLICIES + ")");
        Set<String> seen = new LinkedHashSet<>();
        List<Policy> out = new java.util.ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> policy))
                throw new ApiException(422, "every policy must be an object {name, effect, target?, when?}");
            String name = WriteGates.safeName(str(policy.get("name")).toLowerCase(Locale.ROOT), "policy name");
            if (!seen.add(name)) throw new ApiException(422, "duplicate policy '" + name + "'");
            String effect = str(policy.get("effect"));
            if (!EFFECTS.contains(effect))
                throw new ApiException(422, "policy '" + name + "': effect must be one of " + EFFECTS);
            Set<String> actions = Set.of();
            Set<String> resourceKinds = Set.of();
            Object targetObj = policy.get("target");
            if (targetObj != null) {
                if (!(targetObj instanceof Map<?, ?> target))
                    throw new ApiException(422, "policy '" + name + "': 'target' must be an object {actions?, resourceKinds?}");
                actions = targetValues(name, "actions", target.get("actions"));
                for (String a : actions)
                    if (!ACTIONS.contains(a))
                        throw new ApiException(422, "policy '" + name + "': unknown action '" + a
                                + "' (expected one of " + ACTIONS + ")");
                resourceKinds = targetValues(name, "resourceKinds",
                        target.containsKey("resourceKinds") ? target.get("resourceKinds") : target.get("resource_kinds"));
            }
            String when = str(policy.get("when"));
            if (when.length() > MAX_CONDITION_LENGTH)
                throw new ApiException(422, "policy '" + name + "': 'when' is too long (max "
                        + MAX_CONDITION_LENGTH + " chars)");
            Conditions.Condition condition;
            if (when.isBlank()) {
                condition = ctx -> true;   // no condition — applies whenever the target matches
            } else {
                try {
                    condition = Conditions.parse(when);
                } catch (IllegalArgumentException e) {
                    throw new ApiException(422, "policy '" + name + "': " + e.getMessage());
                }
            }
            out.add(new Policy(name, effect, actions, resourceKinds, when, condition));
        }
        return List.copyOf(out);
    }

    private static Set<String> targetValues(String policy, String field, Object valuesObj) {
        if (valuesObj == null) return Set.of();
        if (!(valuesObj instanceof List<?> values))
            throw new ApiException(422, "policy '" + policy + "': '" + field + "' must be a list");
        if (values.size() > MAX_TARGET_VALUES)
            throw new ApiException(422, "policy '" + policy + "': too many " + field + " (max " + MAX_TARGET_VALUES + ")");
        Set<String> out = new LinkedHashSet<>();
        for (Object v : values) {
            String value = str(v).toLowerCase(Locale.ROOT);
            if (value.isBlank()) throw new ApiException(422, "policy '" + policy + "': blank " + field + " entry");
            out.add(value);
        }
        return out;
    }

    // ── persistence (canonical TOON, crash-safe — AccessRoutes' PUT) ────────────────

    /** Write {@code policies} as {@value #FILE} under {@code configRoot} (snake-case on disk). */
    static void write(Path configRoot, List<Policy> policies) throws IOException {
        List<Map<String, Object>> rows = policies.stream().map(p -> {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("name", p.name());
            r.put("effect", p.effect());
            if (!p.actions().isEmpty() || !p.resourceKinds().isEmpty()) {
                Map<String, Object> target = new LinkedHashMap<>();
                if (!p.actions().isEmpty()) target.put("actions", p.actions().stream().sorted().toList());
                if (!p.resourceKinds().isEmpty())
                    target.put("resource_kinds", p.resourceKinds().stream().sorted().toList());
                r.put("target", target);
            }
            if (!p.when().isBlank()) r.put("when", p.when());
            return r;
        }).toList();
        AtomicFiles.write(configRoot.resolve(FILE),
                JToon.encode(Map.of("policies", rows)).getBytes(StandardCharsets.UTF_8), ".access-policies-");
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
