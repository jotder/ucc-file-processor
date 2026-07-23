package com.gamma.control;

import com.gamma.api.PublicApi;
import com.gamma.util.AtomicFiles;
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
 * Role definitions — the role → capabilities/data-scopes table behind every authenticated grant
 * (RBAC R1, {@code docs/superpower/rbac-abac-plan.md} §3). Authorable as a per-space settings doc
 * {@value #FILE} (settings-doc discipline like {@code branding.toon}/{@code nav-menus.toon});
 * {@link #SEED} carries the shipped defaults, and an authored doc overrides <b>per role name</b> —
 * a role absent from the doc keeps its seed grants, a role authored with an empty capability list is
 * revoked. The security module's {@code RoleMapper} resolves grants through {@link #effective},
 * re-reading on file change, so a role edit applies to the next request — no restart.
 *
 * <p><b>Fail-closed:</b> an existing-but-unreadable doc suspends ALL role grants (every role resolves
 * to nothing) rather than silently falling back to the seed — an authored revocation must never
 * quietly reopen (mirrors the plan's "an unparseable stored policy denies, loudly").
 *
 * <p>The core stays auth-free: this class only stores and serves the table ({@code AccessRoutes}
 * authors it, any edition can read it); <em>enforcement</em> lives behind the {@link Authenticator}
 * seam in the Standard edition's security module, which resolves the per-request table via
 * {@link #effective(HttpExchange)} — {@code ControlApi} stamps the request's bound-space config root
 * as {@link #ATTR_CONFIG_ROOT} before invoking the authenticator, so per-space role docs resolve at
 * token-validation time.
 */
@PublicApi(since = "5.0.0")
public final class Roles {
    private Roles() {}

    private static final Logger LOG = LoggerFactory.getLogger(Roles.class);

    /** Exchange attribute carrying the request's bound-space config root (a {@link Path}), stamped by
     *  {@code ControlApi} before authentication so an {@link Authenticator} can resolve the space's
     *  authored role doc. Absent/null ⇒ {@link #effective} serves the seed table. */
    public static final String ATTR_CONFIG_ROOT = "inspecto.roles.configRoot";

    static final String FILE = "roles.toon";

    // ── capability vocabulary (must stay congruent with the withCapability route gates; R4 will
    //    pin that congruence with a manifest test) ─────────────────────────────────────────────
    public static final String CAN_AUTHOR_WORKBENCH    = "canAuthorWorkbench";
    public static final String CAN_OPERATE_RUNS        = "canOperateRuns";
    public static final String CAN_TRIAGE_REQUIREMENTS = "canTriageRequirements";
    public static final String CAN_ONBOARD_CONNECTIONS = "canOnboardConnections";
    public static final String CAN_CONFIGURE_ACCESS    = "canConfigureAccess";
    public static final String CAN_AUTHOR_ALERT_RULES  = "canAuthorAlertRules";
    public static final String CAN_OFFER_DATASETS      = "canOfferDatasets";
    public static final String CAN_REQUEST_SHARES      = "canRequestShares";
    public static final String CAN_APPROVE_SHARES      = "canApproveShares";

    static final Set<String> KNOWN_CAPABILITIES = Set.of(
            CAN_AUTHOR_WORKBENCH, CAN_OPERATE_RUNS, CAN_TRIAGE_REQUIREMENTS, CAN_ONBOARD_CONNECTIONS,
            CAN_CONFIGURE_ACCESS, CAN_AUTHOR_ALERT_RULES, CAN_OFFER_DATASETS, CAN_REQUEST_SHARES,
            CAN_APPROVE_SHARES);

    private static final int MAX_ROLES = 200;
    private static final int MAX_SCOPES = 64;

    /** One role's grants. {@code dataScopes == null} ⇒ the role contributes no data scoping (SEC-7d
     *  unscoped); an empty set is the fail-closed "untyped objects only" scoping. */
    public record Def(Set<String> capabilities, Set<String> dataScopes) {
        public Def {
            capabilities = Set.copyOf(capabilities);
            dataScopes = dataScopes == null ? null : Set.copyOf(dataScopes);
        }
    }

    /**
     * The shipped default table — {@code rbac-groundwork.md} §3's taxonomy, corrected 2026-07-23 to
     * cover the full route capability vocabulary (the old {@code RoleMapper} switch predated the
     * access-config / alert-rule / exchange-sharing gates, leaving five capabilities no role granted —
     * including {@code canConfigureAccess}, without which a fresh deployment could never author this
     * very table). Builder roles author (workbench, alert rules, dataset offers); Ops operate and
     * request shares; Admin owns the credential/governance surface (connections, access config, share
     * approval); {@code business} is a recognised role that grants nothing.
     */
    static final Map<String, Def> SEED = seed();

    private static Map<String, Def> seed() {
        Set<String> builder = Set.of(CAN_AUTHOR_WORKBENCH, CAN_AUTHOR_ALERT_RULES,
                CAN_OFFER_DATASETS, CAN_REQUEST_SHARES);
        Set<String> ops = Set.of(CAN_OPERATE_RUNS, CAN_REQUEST_SHARES);
        Map<String, Def> m = new LinkedHashMap<>();
        m.put("pipeline-developer", new Def(builder, null));
        m.put("app-developer", new Def(builder, null));
        m.put("developer", new Def(builder, null));
        m.put("operations", new Def(ops, null));
        m.put("support", new Def(ops, null));
        m.put("admin", new Def(Set.of(CAN_ONBOARD_CONNECTIONS, CAN_CONFIGURE_ACCESS, CAN_APPROVE_SHARES), null));
        m.put("power", new Def(Set.of(CAN_AUTHOR_WORKBENCH, CAN_AUTHOR_ALERT_RULES, CAN_OPERATE_RUNS,
                CAN_OFFER_DATASETS, CAN_REQUEST_SHARES), null));
        m.put("super", new Def(KNOWN_CAPABILITIES, null));
        m.put("business", new Def(Set.of(), null));
        return java.util.Collections.unmodifiableMap(m);   // keeps seed iteration order
    }

    // ── resolution ───────────────────────────────────────────────────────────────

    /** The authored doc (authored roles only, no seed merge) + its readability. */
    record Doc(Map<String, Def> authored, boolean unreadable) {
        static final Doc ABSENT = new Doc(Map.of(), false);
    }

    private record Cached(long mtime, long size, Doc doc) {}

    private static final ConcurrentHashMap<Path, Cached> CACHE = new ConcurrentHashMap<>();

    /** Per-request table for an {@link Authenticator}: resolves the bound space's authored doc via
     *  {@link #ATTR_CONFIG_ROOT} (seed-only when unset). Never null; see class doc for merge rules. */
    public static Map<String, Def> effective(HttpExchange ex) {
        return effective(ex.getAttribute(ATTR_CONFIG_ROOT) instanceof Path p ? p : null);
    }

    /** The effective table for {@code configRoot}: authored roles overlaid on {@link #SEED} per role
     *  name; seed-only when {@code configRoot} is null or no doc exists; empty (all grants suspended,
     *  fail-closed) when a doc exists but cannot be read. */
    static Map<String, Def> effective(Path configRoot) {
        Doc doc = load(configRoot);
        if (doc.unreadable()) return Map.of();
        if (doc.authored().isEmpty()) return SEED;
        Map<String, Def> merged = new LinkedHashMap<>(SEED);
        merged.putAll(doc.authored());
        return java.util.Collections.unmodifiableMap(merged);
    }

    /** The authored doc at {@code configRoot} (mtime/size-cached — an on-disk edit or an
     *  {@code AccessRoutes} PUT is picked up on the next read, no restart). */
    static Doc load(Path configRoot) {
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
            LOG.warn("roles: cannot stat {} — suspending role grants (fail-closed): {}", file, e.toString());
            return new Doc(Map.of(), true);
        }
    }

    private static Doc parseFile(Path file) {
        try {
            Map<String, Object> m = ToonHelper.load(file.toString());
            return new Doc(validate(m.get("roles")), false);
        } catch (Exception e) {
            LOG.warn("roles: {} is unreadable — suspending ALL role grants (fail-closed) until fixed: {}",
                    file, e.toString());
            return new Doc(Map.of(), true);
        }
    }

    // ── validation (shared by the PUT route and the file parser — one grammar) ──────

    /** Parse+validate a {@code roles} list (wire {@code dataScopes} and on-disk {@code data_scopes}
     *  both accepted) into name → {@link Def}. Throws {@link ApiException} 422 on any violation. */
    static Map<String, Def> validate(Object rolesObj) {
        if (!(rolesObj instanceof List<?> raw))
            throw new ApiException(422, "role settings require a 'roles' list");
        if (raw.size() > MAX_ROLES)
            throw new ApiException(422, "too many roles (max " + MAX_ROLES + ")");
        Map<String, Def> out = new LinkedHashMap<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> role))
                throw new ApiException(422, "every role must be an object {name, capabilities, dataScopes?}");
            String name = WriteGates.safeName(str(role.get("name")).toLowerCase(Locale.ROOT), "role name");
            if (out.containsKey(name)) throw new ApiException(422, "duplicate role '" + name + "'");
            out.put(name, new Def(capabilities(name, role.get("capabilities")),
                    dataScopes(name, role.containsKey("dataScopes") ? role.get("dataScopes") : role.get("data_scopes"))));
        }
        return out;
    }

    private static Set<String> capabilities(String role, Object capsObj) {
        if (capsObj == null) return Set.of();
        if (!(capsObj instanceof List<?> caps))
            throw new ApiException(422, "role '" + role + "': 'capabilities' must be a list");
        Set<String> out = new LinkedHashSet<>();
        for (Object c : caps) {
            String cap = str(c);
            if (!KNOWN_CAPABILITIES.contains(cap))
                throw new ApiException(422, "role '" + role + "': unknown capability '" + cap
                        + "' (expected one of " + KNOWN_CAPABILITIES + ")");
            out.add(cap);
        }
        return out;
    }

    private static Set<String> dataScopes(String role, Object scopesObj) {
        if (scopesObj == null) return null;   // key absent — the role contributes no scoping
        if (!(scopesObj instanceof List<?> scopes))
            throw new ApiException(422, "role '" + role + "': 'dataScopes' must be a list");
        if (scopes.size() > MAX_SCOPES)
            throw new ApiException(422, "role '" + role + "': too many dataScopes (max " + MAX_SCOPES + ")");
        Set<String> out = new LinkedHashSet<>();
        for (Object s : scopes) {
            String scope = str(s).toLowerCase(Locale.ROOT);
            if (scope.isBlank())
                throw new ApiException(422, "role '" + role + "': blank data scope");
            out.add(scope);
        }
        return out;
    }

    // ── persistence (canonical TOON, crash-safe — AccessRoutes' PUT) ────────────────

    /** Write {@code authored} as {@value #FILE} under {@code configRoot} (snake-case on disk). */
    static void write(Path configRoot, Map<String, Def> authored) throws IOException {
        List<Map<String, Object>> roles = authored.entrySet().stream().map(e -> {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("name", e.getKey());
            r.put("capabilities", List.copyOf(e.getValue().capabilities()));
            if (e.getValue().dataScopes() != null) r.put("data_scopes", List.copyOf(e.getValue().dataScopes()));
            return r;
        }).toList();
        AtomicFiles.write(configRoot.resolve(FILE),
                JToon.encode(Map.of("roles", roles)).getBytes(StandardCharsets.UTF_8), ".roles-");
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
