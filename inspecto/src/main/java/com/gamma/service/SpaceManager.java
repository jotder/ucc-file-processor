package com.gamma.service;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.StabilityGate;
import com.gamma.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The top-level container that hosts many isolated {@link SpaceContext spaces} in one process — the runtime
 * replacement for the single {@link CollectorService} that {@code ControlApi.main} used to build. Each space is
 * fully isolated (its own service, stores, scheduler, event log); cross-space routing of the per-process
 * singletons (event log / metric label / connection registry / stability gate / acquisition ledger) is by the
 * space MDC the execution paths set (Stage 3a).
 *
 * <p>Two boot modes:
 * <ul>
 *   <li>{@link #single(CollectorService)} — wrap one already-built service as the {@code default} space. The
 *       long-standing single-tenant / CLI path and every existing test; behaviour is unchanged.</li>
 *   <li>{@link #discover(Path)} — scan a container root ({@code -Dspaces.root}) for {@code spaces/<id>/} dirs
 *       (a dir with a {@code config/} subtree) and boot each via {@link SpaceBootstrap}, warning-and-skipping a
 *       bad one so one broken space never blocks the others.</li>
 * </ul>
 *
 * <p>In {@link #discover} mode the container root is remembered, so spaces can be {@link #create created} and
 * {@link #delete deleted} at runtime (no restart); {@link #single} mode hosts exactly one space and rejects both.
 * The {@code /spaces/{id}} request seam (ControlApi, Stage 4) routes a request to a chosen space; an un-scoped
 * request resolves {@link #current()} (the {@code default} or sole space).
 *
 * <p>{@link AutoCloseable}: {@link #close()} drains every space in turn (the existing drain-first
 * {@link CollectorService#close()}).
 */
public final class SpaceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpaceManager.class);
    private static final SpaceId DEFAULT = SpaceId.of(EventLog.DEFAULT_SPACE_ID);

    /** The convention subdirectories a space owns under its root (also what {@link SpaceRoot} addresses).
     *  Authored flows live under {@code config/flows/} (minted on first write), not a top-level dir. */
    private static final List<String> SPACE_SUBDIRS = List.of("config", "data", "audit", "duckdb");

    /** S7: max time to wait for one space's drain-and-close before abandoning it. A hung agent/DB close
     *  must never block a runtime {@link #delete} nor, via the shutdown hook, JVM exit. */
    private static final long CLOSE_DEADLINE_MS = 10_000;

    private final ConcurrentHashMap<SpaceId, SpaceContext> spaces = new ConcurrentHashMap<>();
    /** Serialises the rare create/delete admin mutations; reads ({@link #space}/{@link #current}) stay lock-free. */
    private final Object lifecycleLock = new Object();
    /** The container root ({@code -Dspaces.root}) new spaces are created under; {@code null} in single-tenant mode. */
    private volatile Path spacesRoot;

    private SpaceManager() {}

    /** Wrap a single already-built service as the {@code default} space (single-tenant / CLI / tests). */
    public static SpaceManager single(CollectorService service) {
        SpaceManager m = new SpaceManager();
        SpaceContext ctx = new SpaceContext(DEFAULT, SpaceRoot.legacy(),
                new SpaceContext.SpaceManifest(DEFAULT.value(), "", ""), service);
        m.spaces.put(DEFAULT, ctx);
        return m;
    }

    /** Boot every space under {@code spacesRoot} (each a dir with a {@code config/} subtree); a bad one is skipped. */
    public static SpaceManager discover(Path spacesRoot) throws IOException {
        SpaceManager m = new SpaceManager();
        m.spacesRoot = spacesRoot.toAbsolutePath().normalize();   // remembered so runtime create/delete can mint/remove dirs
        if (!Files.isDirectory(spacesRoot)) {
            log.warn("Spaces root {} does not exist — no spaces booted", spacesRoot.toAbsolutePath());
            return m;
        }
        try (Stream<Path> dirs = Files.list(spacesRoot)) {
            dirs.filter(Files::isDirectory)
                .filter(d -> Files.isDirectory(d.resolve("config")))
                .sorted()
                .forEach(m::bootQuietly);
        }
        log.info("SpaceManager: {} space(s) booted from {}", m.spaces.size(), spacesRoot.toAbsolutePath());
        return m;
    }

    private void bootQuietly(Path dir) {
        try {
            SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(dir));
            spaces.put(ctx.id(), ctx);
        } catch (Exception e) {
            log.warn("Skipping space dir {} — failed to load: {}", dir, e.getMessage());
        }
    }

    /** Arm every hosted space's poll loop / schedules. */
    public void startAll() {
        spaces.values().forEach(SpaceContext::start);
    }

    // ── runtime CRUD (no restart) ─────────────────────────────────────────────

    /** Whether this manager can mint/remove spaces at runtime (false for the single-tenant {@link #single} mode). */
    public boolean supportsCrud() {
        return spacesRoot != null;
    }

    /** The container root ({@code -Dspaces.root}) hosted spaces live under, or {@code null} in single-tenant
     *  mode. The Exchange ({@code spaces/_shared/}) is rooted here; a {@code null} disables cross-Space sharing. */
    public Path containerRoot() {
        return spacesRoot;
    }

    /**
     * Create a new space under {@code spaces/<id>/}: make its convention dirs + {@code space.toon} manifest, boot it
     * via {@link SpaceBootstrap}, {@link SpaceContext#start() start} it, and register it — all without a restart.
     * Serialised with {@link #delete}.
     *
     * @throws IllegalStateException when this manager hosts a single space ({@link #single}; no container root), or a
     *                               space with this id (or its directory) already exists
     */
    public SpaceContext create(SpaceId id, String displayName, String description) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        synchronized (lifecycleLock) {
            if (spaces.containsKey(id))
                throw new IllegalStateException("Space already exists: " + id.value());
            Path base = spacesRoot.resolve(id.value());
            if (Files.exists(base))
                throw new IllegalStateException("Space directory already exists: " + base);
            for (String sub : SPACE_SUBDIRS) Files.createDirectories(base.resolve(sub));
            String name = (displayName == null || displayName.isBlank()) ? id.value() : displayName.trim();
            new SpaceContext.SpaceManifest(name, description == null ? "" : description.trim(), Instant.now().toString())
                    .write(base.resolve("space.toon"));

            SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base));
            try {
                ctx.start();
            } catch (RuntimeException e) {
                ctx.close();   // don't leak a started-but-unregistered service
                throw e;
            }
            spaces.put(id, ctx);
            log.info("Created space '{}' at {}", id.value(), base);
            return ctx;
        }
    }

    /**
     * Create a new space seeded from a bundle zip: mint its convention dirs, unpack the bundle's config files into
     * {@code config/} (jailed against zip-slip) and its {@code space.toon} (or a default), then boot + register it —
     * no restart. The fresh {@link SpaceBootstrap} boot discovers every config uniformly, so pipelines, connections
     * and jobs are all live. Serialised with {@link #create}/{@link #delete}.
     *
     * @throws IllegalArgumentException if the zip is not a valid bundle (delegated from {@link BundleImporter#parse})
     * @throws IllegalStateException    single-tenant mode, or a space with this id / directory already exists
     */
    public SpaceContext createFromBundle(SpaceId id, byte[] zip) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        BundleImporter.Bundle bundle = BundleImporter.parse(zip);   // validates the manifest before touching disk
        synchronized (lifecycleLock) {
            if (spaces.containsKey(id))
                throw new IllegalStateException("Space already exists: " + id.value());
            Path base = spacesRoot.resolve(id.value());
            if (Files.exists(base))
                throw new IllegalStateException("Space directory already exists: " + base);
            for (String sub : SPACE_SUBDIRS) Files.createDirectories(base.resolve(sub));
            BundleImporter.writeConfig(bundle, base.resolve("config"));
            Path manifest = base.resolve("space.toon");
            if (bundle.spaceToon() != null) Files.write(manifest, bundle.spaceToon());
            else new SpaceContext.SpaceManifest(id.value(), "", Instant.now().toString()).write(manifest);

            SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base));
            try {
                ctx.start();
            } catch (RuntimeException e) {
                ctx.close();
                throw e;
            }
            spaces.put(id, ctx);
            log.info("Created space '{}' from bundle at {} ({} config file(s))",
                    id.value(), base, bundle.configEntries().size());
            return ctx;
        }
    }

    /** The shipped-template catalog root ({@code <spacesRoot>/_templates}) — an underscore sentinel like
     *  {@code _shared}, so space discovery never admits it. {@code null} in single-tenant mode. */
    private Path templatesRoot() {
        return spacesRoot == null ? null : spacesRoot.resolve("_templates");
    }

    /**
     * The space templates this server ships: one gallery entry per {@code _templates/<id>/template.toon}
     * ({@code {id, name, tagline, description, icon, contents[]}} — the UI's {@code SpaceTemplateInfo} shape).
     * Empty in single-tenant mode or when no templates directory exists; an unreadable template is warned
     * and skipped (the rest still list).
     */
    public List<Map<String, Object>> templates() throws IOException {
        Path root = templatesRoot();
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path meta = dir.resolve("template.toon");
                if (!Files.exists(meta)) continue;
                try {
                    Map<String, Object> m = com.gamma.util.ToonHelper.load(meta.toString());
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", dir.getFileName().toString());
                    info.put("name", com.gamma.util.ToonHelper.opt(m, "name", dir.getFileName().toString()));
                    info.put("tagline", com.gamma.util.ToonHelper.opt(m, "tagline", ""));
                    info.put("description", com.gamma.util.ToonHelper.opt(m, "description", ""));
                    info.put("icon", com.gamma.util.ToonHelper.opt(m, "icon", "heroicons_outline:cube"));
                    info.put("contents", m.get("contents") instanceof List<?> l ? l : List.of());
                    out.add(info);
                } catch (Exception bad) {
                    log.warn("Skipping unreadable space template {}: {}", dir, bad.toString());
                }
            }
        }
        return out;
    }

    /**
     * Create a new space seeded from a shipped template: mint the convention dirs, copy the template's
     * {@code config/} tree with every {@code ${SPACE}} token in a {@code .toon} rewritten to the new id
     * (template configs address their own space as {@code spaces/${SPACE}/…}), copy {@code data/} verbatim
     * (pristine samples), then boot + register — the fresh {@link SpaceBootstrap} discovers every copied
     * config uniformly, exactly like {@link #createFromBundle}. Serialised with {@link #create}/{@link #delete}.
     *
     * @throws IllegalArgumentException when no template with {@code templateId} exists
     * @throws IllegalStateException    single-tenant mode, or a space with this id / directory already exists
     */
    public SpaceContext createFromTemplate(SpaceId id, String displayName, String description,
                                           String templateId) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        Path tpl = templatesRoot().resolve(templateId);
        Path meta = tpl.resolve("template.toon");
        if (!Files.isDirectory(tpl) || !Files.exists(meta))
            throw new IllegalArgumentException("no such space template '" + templateId + "'");
        Map<String, Object> tplMeta = com.gamma.util.ToonHelper.load(meta.toString());
        synchronized (lifecycleLock) {
            if (spaces.containsKey(id))
                throw new IllegalStateException("Space already exists: " + id.value());
            Path base = spacesRoot.resolve(id.value());
            if (Files.exists(base))
                throw new IllegalStateException("Space directory already exists: " + base);
            for (String sub : SPACE_SUBDIRS) Files.createDirectories(base.resolve(sub));
            copyTemplateTree(tpl.resolve("config"), base.resolve("config"), id.value(), true);
            copyTemplateTree(tpl.resolve("data"), base.resolve("data"), id.value(), false);
            String name = (displayName == null || displayName.isBlank())
                    ? com.gamma.util.ToonHelper.opt(tplMeta, "name", id.value()) : displayName.trim();
            String desc = (description == null || description.isBlank())
                    ? com.gamma.util.ToonHelper.opt(tplMeta, "tagline", "") : description.trim();
            new SpaceContext.SpaceManifest(name, desc, Instant.now().toString()).write(base.resolve("space.toon"));

            SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base));
            try {
                ctx.start();
            } catch (RuntimeException e) {
                ctx.close();
                throw e;
            }
            spaces.put(id, ctx);
            log.info("Created space '{}' from template '{}' at {}", id.value(), templateId, base);
            return ctx;
        }
    }

    /** Copy {@code src/**} to {@code dst/**}; when {@code rewriteToon}, each {@code .toon} is copied as text
     *  with {@code ${SPACE}} replaced by {@code spaceId} (all other files byte-for-byte). No-op if absent. */
    private static void copyTemplateTree(Path src, Path dst, String spaceId, boolean rewriteToon) throws IOException {
        if (!Files.isDirectory(src)) return;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.sorted().toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else if (rewriteToon && p.getFileName().toString().endsWith(".toon")) {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, Files.readString(p).replace("${SPACE}", spaceId));
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Update a hosted space's display metadata (name + description) in place — rewrite its {@code space.toon} and
     * swap the in-memory manifest. The id, directory tree and running service are untouched (the id/folder is
     * immutable). Serialised with {@link #create}/{@link #delete}.
     *
     * @return the updated space, or {@code null} when no space with {@code id} is hosted
     * @throws IllegalStateException when this manager hosts a single space ({@link #single}; no container root)
     */
    public SpaceContext update(SpaceId id, String displayName, String description) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        synchronized (lifecycleLock) {
            SpaceContext ctx = spaces.get(id);
            if (ctx == null) return null;
            String name = (displayName == null || displayName.isBlank()) ? id.value() : displayName.trim();
            SpaceContext.SpaceManifest updated = new SpaceContext.SpaceManifest(
                    name, description == null ? "" : description.trim(), ctx.manifest().createdAt());
            updated.write(spacesRoot.resolve(id.value()).resolve("space.toon"));
            ctx.updateManifest(updated);
            log.info("Updated space '{}' display metadata", id.value());
            return ctx;
        }
    }

    /**
     * Remove a hosted space: deregister it first (new requests {@code 404} at once), then drain-and-close its service
     * (the existing {@link CollectorService#close()}). When {@code purge} is set, the space's directory tree
     * ({@code config/data/audit/duckdb/flows} + manifest) is then deleted from disk; otherwise the files are left for
     * a later manual cleanup or re-discovery. Serialised with {@link #create}.
     *
     * @return {@code false} when no space with {@code id} is hosted (nothing to do)
     * @throws IllegalStateException when this manager hosts a single space ({@link #single})
     */
    public boolean delete(SpaceId id, boolean purge) throws IOException {
        if (spacesRoot == null)
            throw new IllegalStateException("This server hosts a single space; set -Dspaces.root to manage many");
        SpaceContext ctx;
        synchronized (lifecycleLock) {
            ctx = spaces.remove(id);
        }
        if (ctx == null) return false;
        // S7: the space is already deregistered — a hung or failing drain-and-close must not abort the
        // rest of teardown, or the process-wide per-space registries below would leak (half-removed space).
        closeWithDeadline(ctx);
        AcquisitionLedgers.unregister(id.value());   // release the per-space ledger SpaceBootstrap registered (+ its DB handle)
        ConnectionRegistry.forget(id.value());        // drop the space's connection profiles (process-wide static map)
        StabilityGate.forget(id.value());             // drop the space's file-stability gate + its retained sightings
        com.gamma.pipeline.DecisionRules.forget(id.value());   // drop the space's decision-rule registry root
        if (purge) {
            Path base = spacesRoot.resolve(id.value()).normalize();   // SpaceId is jailed: no separators/.. can escape
            if (base.startsWith(spacesRoot) && Files.isDirectory(base)) deleteRecursively(base);
            log.info("Deleted + purged space '{}' ({})", id.value(), base);
        } else {
            log.info("Deleted space '{}' (files left on disk)", id.value());
        }
        return true;
    }

    /** Recursively delete {@code dir} (deepest entries first). */
    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** The hosted space with {@code id}, if present. */
    public Optional<SpaceContext> space(SpaceId id) {
        return Optional.ofNullable(spaces.get(id));
    }

    /**
     * The space served for an un-scoped request: the {@code default} space, or — when discovery booted no
     * {@code default} — the first registered space. The {@code /spaces/{id}} seam (later stage) supersedes this.
     *
     * @throws IllegalStateException when no spaces are hosted
     */
    public SpaceContext current() {
        SpaceContext c = spaces.get(DEFAULT);
        if (c != null) return c;
        return spaces.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No spaces are hosted"));
    }

    /** Every hosted space. */
    public Collection<SpaceContext> all() {
        return spaces.values();
    }

    public int size() {
        return spaces.size();
    }

    @Override
    public void close() {
        for (SpaceContext c : spaces.values()) {
            closeWithDeadline(c);   // S7: bound each drain so one hung space can't stall JVM shutdown
        }
        spaces.clear();
    }

    /**
     * S7: drain-and-close one space's service under {@link #CLOSE_DEADLINE_MS}, best-effort. The close runs
     * on a daemon thread; this returns as soon as it finishes or the deadline lapses. A close still running
     * past the deadline is abandoned — being a daemon, it can never hold the JVM open — and any exception it
     * throws is logged, not propagated, so teardown always continues. Never throws.
     */
    private void closeWithDeadline(SpaceContext ctx) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();   // preserve space-log routing on the close thread
        Thread t = new Thread(() -> {
            if (mdc != null) MDC.setContextMap(mdc);
            try {
                ctx.close();
            } catch (Exception e) {
                log.warn("Error closing space '{}': {}", ctx.id(), e.getMessage());
            }
        }, "space-close-" + ctx.id());
        t.setDaemon(true);
        t.start();
        try {
            t.join(CLOSE_DEADLINE_MS);
            if (t.isAlive())
                log.warn("Close of space '{}' did not finish within {} ms; abandoning it and continuing teardown",
                        ctx.id(), CLOSE_DEADLINE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
