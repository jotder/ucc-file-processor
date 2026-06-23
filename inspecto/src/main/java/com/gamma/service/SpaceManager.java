package com.gamma.service;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The top-level container that hosts many isolated {@link SpaceContext spaces} in one process — the runtime
 * replacement for the single {@link SourceService} that {@code ControlApi.main} used to build. Each space is
 * fully isolated (its own service, stores, scheduler, event log); cross-space routing of the per-process
 * singletons (event log / metric label / connection registry / stability gate / acquisition ledger) is by the
 * space MDC the execution paths set (Stage 3a).
 *
 * <p>Two boot modes:
 * <ul>
 *   <li>{@link #single(SourceService)} — wrap one already-built service as the {@code default} space. The
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
 * {@link SourceService#close()}).
 */
public final class SpaceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpaceManager.class);
    private static final SpaceId DEFAULT = SpaceId.of(EventLog.DEFAULT_SPACE_ID);

    /** The convention subdirectories a space owns under its root (also what {@link SpaceRoot} addresses). */
    private static final List<String> SPACE_SUBDIRS = List.of("config", "data", "audit", "duckdb", "flows");

    private final ConcurrentHashMap<SpaceId, SpaceContext> spaces = new ConcurrentHashMap<>();
    /** Serialises the rare create/delete admin mutations; reads ({@link #space}/{@link #current}) stay lock-free. */
    private final Object lifecycleLock = new Object();
    /** The container root ({@code -Dspaces.root}) new spaces are created under; {@code null} in single-tenant mode. */
    private volatile Path spacesRoot;

    private SpaceManager() {}

    /** Wrap a single already-built service as the {@code default} space (single-tenant / CLI / tests). */
    public static SpaceManager single(SourceService service) {
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

    /**
     * Remove a hosted space: deregister it first (new requests {@code 404} at once), then drain-and-close its service
     * (the existing {@link SourceService#close()}). When {@code purge} is set, the space's directory tree
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
        ctx.close();
        AcquisitionLedgers.unregister(id.value());   // release the per-space ledger SpaceBootstrap registered (+ its DB handle)
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
            try {
                c.close();
            } catch (Exception e) {
                log.warn("Error closing space '{}': {}", c.id(), e.getMessage());
            }
        }
        spaces.clear();
    }
}
