package com.gamma.service;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Boot-time check that a space's on-disk tree honours the <em>storage-layout contract</em>
 * ({@code docs/superpower/storage-layout-and-sharing-plan.md} §2): the four persistence axes —
 * {@code config/} (authored definitions), {@code data/} (the data plane), {@code audit/} (ledgers) and
 * {@code duckdb/} (embedded-DB state) — must stay separate, and the canonical subdirs must exist. These
 * are the same axes {@link SpaceMigrator} organises the flat layout into.
 *
 * <p>A violation is an <b>advisory, never a boot failure</b>: {@link #verify} emits one
 * {@link EventType#LAYOUT_CONTRACT_VIOLATION} {@link EventLevel#WARN} event per finding through
 * {@link EventLog#global()} (the space's own log is not yet registered at boot) and returns them, but it
 * never throws — a non-conforming space still boots, matching the warning-and-skipping posture of
 * {@link SpaceManager#discover}. The scan is deliberately cheap: it walks only the small {@code config/}
 * and {@code audit/} trees plus the space's top-level entries, never the (potentially large) {@code data/}
 * partition tree.
 */
final class SpaceLayoutContract {

    /** DuckDB/embedded-store file suffixes — these belong <em>only</em> under {@code duckdb/}. */
    private static final Set<String> DB_SUFFIXES = Set.of(".duckdb", ".db", ".wal");

    /** The known top-level entries of a conforming space tree (everything else is flagged). */
    private static final Set<String> KNOWN_TOP_LEVEL =
            Set.of("config", "data", "audit", "duckdb", "flows", "views", "space.toon");

    /** Canonical subdirs a healthy space owns (beyond {@code config/}, which discovery already requires). */
    private static final List<String> REQUIRED_SUBDIRS = List.of("data", "audit", "duckdb");

    private SpaceLayoutContract() {}

    /** One departure from the contract — the {@code kind} rule it breaks and the offending {@code path}. */
    record Violation(String kind, Path path, String message) {}

    /**
     * Scan {@code root}'s tree against the contract, emit a WARN event per finding, and return them.
     * A {@linkplain SpaceRoot#legacy() legacy} (single-tenant) root has no contract tree and is skipped.
     */
    static List<Violation> verify(SpaceRoot root) {
        List<Violation> found = new ArrayList<>();
        if (root == null || root.config() == null) return found;   // legacy/flat layout: no contract to check
        Path base = root.base();
        String space = root.id();

        // 1. Canonical subdirs present.
        for (String sub : REQUIRED_SUBDIRS) {
            Path dir = base.resolve(sub);
            if (!Files.isDirectory(dir))
                found.add(new Violation("missing-subdir", dir,
                        "canonical subdir '" + sub + "/' is missing"));
        }

        // 2. Axes never mix: embedded-DB state must live only under duckdb/. Scan the small config/ and
        //    audit/ trees (data/ is skipped — it can be large, and DB files there are not the migrator's axis).
        scanForDbFiles(base.resolve("config"), found);
        scanForDbFiles(base.resolve("audit"), found);

        // 3. Unexpected top-level entries (dotfiles/atomic-write temp files ignored).
        try (Stream<Path> top = Files.list(base)) {
            top.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) return;
                if (!KNOWN_TOP_LEVEL.contains(name))
                    found.add(new Violation("unexpected-entry", p,
                            "entry '" + name + "' is not part of the space-layout contract"));
            });
        } catch (IOException ignore) {
            // best effort — a scan failure must never break boot
        }

        for (Violation v : found) emit(space, v);
        return found;
    }

    /** Flag any {@link #DB_SUFFIXES} file anywhere under {@code dir} — those belong under {@code duckdb/}. */
    private static void scanForDbFiles(Path dir, List<Violation> found) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return DB_SUFFIXES.stream().anyMatch(n::endsWith);
                })
                .forEach(p -> found.add(new Violation("db-file-outside-duckdb", p,
                        "embedded-DB state file outside duckdb/ — the config/data/audit/duckdb axes must not mix")));
        } catch (IOException ignore) {
            // best effort
        }
    }

    private static void emit(String space, Violation v) {
        EventLog.global().emit(Event.builder(EventType.LAYOUT_CONTRACT_VIOLATION)
                .level(EventLevel.WARN)
                .source(SpaceLayoutContract.class.getName())
                .message(v.message())
                .attr("space", space)
                .attr("kind", v.kind())
                .attr("path", v.path().toString()));
    }
}
