package com.gamma.service;

import com.gamma.event.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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
 * <p>{@link AutoCloseable}: {@link #close()} drains every space in turn (the existing drain-first
 * {@link SourceService#close()}). The {@code /spaces/{id}} request seam that routes to a chosen space is added
 * in a later stage; until then {@link #current()} resolves the {@code default} (or sole) space.
 */
public final class SpaceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpaceManager.class);
    private static final SpaceId DEFAULT = SpaceId.of(EventLog.DEFAULT_SPACE_ID);

    private final ConcurrentHashMap<SpaceId, SpaceContext> spaces = new ConcurrentHashMap<>();

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
