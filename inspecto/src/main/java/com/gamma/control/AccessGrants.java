package com.gamma.control;

import com.gamma.api.PublicApi;
import com.gamma.util.ToonHelper;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side Access-Profile enforcement (RBAC R2, {@code docs/superpower/rbac-abac-plan.md} §3):
 * resolves which capabilities a subject's roles are <b>denied</b> by the saved {@code subjectType:
 * role} Access Profiles over the Access Catalog — the same catalog/profile documents the Lens UI
 * has always shaped visibility with ({@code AccessRoutes}), now enforced where it counts.
 *
 * <p><b>Where it applies (design decision, 2026-07-23):</b> at <em>authentication</em> time — the
 * security module strips denied capabilities before the {@link Subject} is built, so enforcement
 * flows through the existing {@code requireCapability} gates unchanged, {@code Subject} keeps
 * speaking capabilities-only (guideline 13 — role names never leave the authenticator), and the v1
 * envelope's {@code permissions}/{@code /bootstrap} automatically report the <em>effective</em>
 * grants the UI needs to constrain its nav. There is no separate authorize middleware stage.
 *
 * <p><b>Grant semantics</b> (GLOSSARY §1-A "Grant", unchanged from the UI): per profile, a node's
 * effective grant is its nearest-ancestor explicit {@code allow|deny}, root default <b>allow</b>.
 * Across a subject's several roles, access <b>unions</b> (RBAC norm — holding an additional role
 * never reduces access): a capability is denied only when <em>every</em> held role resolves
 * {@code deny} on <em>every</em> catalog action node bound to it. A held role with no saved profile
 * resolves allow everywhere (so profiles-for-some-roles never surprise-deny a multi-role subject).
 * Only capabilities bound to a catalog action node can be profile-denied; the un-cataloged rest is
 * untouched. <b>Fail-closed:</b> an existing-but-unreadable profile contributes no allows for its
 * role; an unreadable catalog (with every role profiled) denies every known capability, loudly.
 */
@PublicApi(since = "5.0.0")
public final class AccessGrants {
    private AccessGrants() {}

    private static final Logger LOG = LoggerFactory.getLogger(AccessGrants.class);

    private record CachedToon(long mtime, long size, Map<String, Object> content) {}

    private static final ConcurrentHashMap<Path, CachedToon> CACHE = new ConcurrentHashMap<>();

    /** Per-request resolution for an {@link Authenticator} — reads the bound space's config root
     *  from {@link Roles#ATTR_CONFIG_ROOT} (nothing denied when unset). */
    public static Set<String> deniedCapabilities(HttpExchange ex, Collection<String> roleNames) {
        return deniedCapabilities(
                ex.getAttribute(Roles.ATTR_CONFIG_ROOT) instanceof Path p ? p : null, roleNames);
    }

    /** The capabilities denied to a subject holding {@code roleNames} (case-insensitive) under
     *  {@code configRoot}'s catalog + role profiles. Empty when there is nothing to enforce. */
    static Set<String> deniedCapabilities(Path configRoot, Collection<String> roleNames) {
        if (configRoot == null || roleNames == null || roleNames.isEmpty()) return Set.of();

        // Union semantics short-circuit: a role with no saved profile allows everywhere.
        Path profilesDir = configRoot.resolve("registry").resolve("access-profiles");
        List<ProfileGrants> profiles = new java.util.ArrayList<>();
        for (String role : new LinkedHashSet<>(roleNames)) {
            String name = role.toLowerCase(Locale.ROOT);
            if (name.startsWith("case:")) continue;   // scoping pseudo-roles never carry profiles
            // Claim-supplied names must never reach resolve() unchecked (path jail). A name that
            // cannot be a stored profile id is an unprofiled role — allows everywhere, so done.
            if (!name.matches("[a-z0-9][a-z0-9._-]*") || name.contains("..")) return Set.of();
            Path file = profilesDir.resolve("role-" + name + ".toon");
            if (!Files.exists(file)) return Set.of();
            profiles.add(profileGrants(file));
        }
        if (profiles.isEmpty()) return Set.of();

        Map<String, Object> catalog = read(configRoot.resolve("registry")
                .resolve("access-catalog").resolve("catalog.toon"));
        if (catalog == null) return Set.of();                       // no catalog — nothing bound, nothing denied
        Object nodes = catalog.get("nodes");
        // The lenient TOON parser rarely throws — shape-invalidity IS unreadability (like Roles):
        // an empty parse or a nodes key that isn't a list means the tree can't be resolved.
        if (catalog.isEmpty() || (catalog.containsKey("nodes") && !(nodes instanceof List))) {
            LOG.warn("access: catalog.toon is unreadable and every held role is profiled — "
                    + "denying all capabilities (fail-closed) until it is fixed or re-saved");
            return Roles.KNOWN_CAPABILITIES;
        }
        if (nodes == null) return Set.of();                         // an empty catalog binds nothing

        // capability → allowed by at least one (role, bound action node) pair?
        Map<String, Boolean> capAllowed = new java.util.LinkedHashMap<>();
        for (ProfileGrants p : profiles)
            walk(nodes, true, p, capAllowed);
        Set<String> denied = new LinkedHashSet<>();
        capAllowed.forEach((cap, allowed) -> { if (!allowed) denied.add(cap); });
        return denied;
    }

    /** DFS with nearest-ancestor inheritance: {@code inheritedAllow} is the parent's effective
     *  grant; an explicit grant on this node overrides it for the node and its subtree. */
    private static void walk(Object nodesObj, boolean inheritedAllow, ProfileGrants p,
                             Map<String, Boolean> capAllowed) {
        if (!(nodesObj instanceof List<?> nodes)) return;
        for (Object o : nodes) {
            if (!(o instanceof Map<?, ?> node)) continue;
            boolean allow = inheritedAllow;
            Object explicit = p.grants().get(String.valueOf(node.get("id")));
            if ("allow".equals(explicit)) allow = true;
            else if ("deny".equals(explicit)) allow = false;
            Object capability = node.get("capability");
            if (capability instanceof String cap && !cap.isBlank())
                capAllowed.merge(cap, allow && !p.unreadable(), Boolean::logicalOr);
            walk(node.get("children"), allow, p, capAllowed);
        }
    }

    private record ProfileGrants(Map<?, ?> grants, boolean unreadable) {}

    private static ProfileGrants profileGrants(Path file) {
        Map<String, Object> content = read(file);
        Object grants = content == null ? null : content.get("grants");
        // Shape-invalid = unreadable (the lenient TOON parser turns corruption into wrong types):
        // empty parse, or a grants key that isn't a map, contributes no allows for its role.
        if (content == null || content.isEmpty() || (content.containsKey("grants") && !(grants instanceof Map))) {
            LOG.warn("access: profile {} is unreadable — its role contributes no allows (fail-closed)", file);
            return new ProfileGrants(Map.of(), true);
        }
        return new ProfileGrants(grants instanceof Map<?, ?> g ? g : Map.of(), false);
    }

    /** mtime/size-cached TOON read: {@code null} = file absent; empty map = exists but unreadable. */
    private static Map<String, Object> read(Path file) {
        if (!Files.exists(file)) return null;
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            CachedToon hit = CACHE.get(file);
            if (hit != null && hit.mtime() == mtime && hit.size() == size) return hit.content();
            Map<String, Object> parsed;
            try {
                parsed = ToonHelper.load(file.toString());
            } catch (Exception e) {
                LOG.warn("access: {} failed to parse: {}", file, e.toString());
                parsed = Map.of();
            }
            CACHE.put(file, new CachedToon(mtime, size, parsed));
            return parsed;
        } catch (Exception e) {
            LOG.warn("access: cannot stat {}: {}", file, e.toString());
            return Map.of();
        }
    }
}
