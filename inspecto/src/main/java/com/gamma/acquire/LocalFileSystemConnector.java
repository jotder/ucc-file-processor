package com.gamma.acquire;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static com.gamma.acquire.SourceConnector.Capability.*;

/**
 * The built-in connector for the local filesystem — and the parity baseline for the whole SPI.
 *
 * <p>{@link #discover} reproduces the legacy {@link com.gamma.inspector.SourceProcessor#collectCandidates}
 * tree-walk exactly: regular files under the poll root, excluding the engine-managed {@code errors/} and
 * {@code quarantine/} subtrees, matched against the include patterns. With the legacy defaults
 * (includes = the single {@code processing.file_pattern} glob, no excludes, unbounded depth) the candidate
 * set is identical to before — that is the Phase-A acceptance bar.
 *
 * <p>It deliberately does <b>no</b> per-file {@code stat()} at discovery (the walk's own
 * {@code isRegularFile} check is the only one, as before) and creates nothing when the poll root is absent,
 * preserving the read-only, side-effect-free contract that {@code countPending} relies on.
 */
public final class LocalFileSystemConnector implements SourceConnector {

    private final Path pollRoot;
    private final Path errorsDir;
    private final Path quarantineDir;
    /** Optional {@code ready_marker} template (Phase B), e.g. {@code "{name}.done"}; {@code null} ⇒ no gating. */
    private final String readyMarker;

    /** @param pollRoot/{@code errorsDir}/{@code quarantineDir} absolute, normalised engine dirs. */
    public LocalFileSystemConnector(Path pollRoot, Path errorsDir, Path quarantineDir) {
        this(pollRoot, errorsDir, quarantineDir, null);
    }

    /**
     * @param readyMarker a {@code ready_marker} template (Phase B) — a discovered file is {@link Readiness#READY}
     *                    only when its sibling marker exists, {@link Readiness#NOT_READY} otherwise; {@code null}
     *                    leaves readiness {@link Readiness#UNKNOWN} (the engine stabilization path).
     *                    {@code "{name}"} expands to the file name (e.g. {@code "{name}.done"} → {@code feed.csv.done}).
     */
    public LocalFileSystemConnector(Path pollRoot, Path errorsDir, Path quarantineDir, String readyMarker) {
        this.pollRoot = pollRoot;
        this.errorsDir = errorsDir;
        this.quarantineDir = quarantineDir;
        this.readyMarker = (readyMarker == null || readyMarker.isBlank()) ? null : readyMarker.trim();
    }

    @Override
    public String scheme() {
        return "local";
    }

    @Override
    public EnumSet<Capability> capabilities() {
        // Reads in place (≈stream), random-access + resumable (it's a local file), and can delete/move/rename.
        return EnumSet.of(STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME);
    }

    @Override
    public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
        if (!Files.exists(pollRoot)) return List.of();   // missing inbox → nothing, create nothing

        List<PathMatcher> includes = matchers(ctx.includes());
        List<PathMatcher> excludes = matchers(ctx.excludes());
        int depth = ctx.bounded() ? ctx.maxDepth() : Integer.MAX_VALUE;

        List<RemoteFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pollRoot, depth)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(errorsDir))
                .filter(p -> !p.startsWith(quarantineDir))
                .filter(p -> !isMarkerFile(p.getFileName().toString()))   // never ingest a ready-marker sentinel
                .filter(p -> matchesAny(includes, p))
                .filter(p -> excludes.isEmpty() || !matchesAny(excludes, p))
                .forEach(p -> out.add(toRemote(p)));
        } catch (IOException e) {
            throw new AcquisitionException("Failed to scan local source " + pollRoot, e);
        }
        return out;
    }

    @Override
    public Readiness readiness(RemoteFile file) throws AcquisitionException {
        // With a ready_marker configured, readiness is authoritative: process only once the sibling
        // "<name>.done"-style sentinel exists. Without one, defer to the engine's size/mtime stabilization.
        if (readyMarker == null) return Readiness.UNKNOWN;
        Path src = requireLocal(file);
        Path marker = src.resolveSibling(applyTemplate(readyMarker, file.name()));
        return Files.exists(marker) ? Readiness.READY : Readiness.NOT_READY;
    }

    @Override
    public InputStream open(RemoteFile file) throws AcquisitionException {
        try {
            return Files.newInputStream(requireLocal(file));
        } catch (IOException e) {
            throw new AcquisitionException("Cannot open " + file.relativePath(), e);
        }
    }

    @Override
    public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
        Path src = requireLocal(file);
        try {
            if (dest.equals(src)) return src;            // already where it should be — no copy
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return dest;
        } catch (IOException e) {
            throw new AcquisitionException("Cannot stage " + file.relativePath() + " → " + dest, e);
        }
    }

    @Override
    public void post(RemoteFile file, PostAction action) throws AcquisitionException {
        Path src = requireLocal(file);
        try {
            switch (action.kind()) {
                case RETAIN -> { /* leave it */ }
                case DELETE -> Files.deleteIfExists(src);
                case MOVE -> {
                    // Archive relative to the poll *parent* (a sibling of the scanned tree) so a moved file
                    // is not re-discovered next cycle; an absolute archiveTemplate is honoured as-is.
                    Path archiveRoot = Paths.get(action.archiveTemplate());
                    if (!archiveRoot.isAbsolute()) {
                        Path base = pollRoot.getParent() != null ? pollRoot.getParent() : pollRoot;
                        archiveRoot = base.resolve(archiveRoot);
                    }
                    Path target = archiveRoot.resolve(file.relativePath());
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
                }
                case RENAME -> {
                    Path target = src.resolveSibling("processed_" + file.name());
                    Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
                }
                case TAG -> throw new AcquisitionException("Local filesystem does not support metadata tagging");
            }
        } catch (IOException e) {
            throw new AcquisitionException("Post-action " + action.kind() + " failed for " + file.relativePath(), e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private RemoteFile toRemote(Path p) {
        // size/mtime intentionally not stat'd here (see RemoteFile javadoc + class note) — keep discovery
        // I/O at the legacy minimum; later phases fill them when stability/watermark/checksum needs them.
        String rel = pollRoot.relativize(p).toString().replace('\\', '/');
        return new RemoteFile(p.getFileName().toString(), rel,
                RemoteFile.SIZE_UNKNOWN, null, null, null, p);
    }

    private Path requireLocal(RemoteFile file) throws AcquisitionException {
        if (!file.isLocal())
            throw new AcquisitionException("Local connector requires an on-disk path for " + file.relativePath());
        return file.localPath();
    }

    private static boolean matchesAny(List<PathMatcher> matchers, Path p) {
        for (PathMatcher m : matchers) if (m.matches(p)) return true;
        return false;
    }

    /** Expand a {@code ready_marker} template for one file: {@code "{name}"} → the file name (else suffix it). */
    private static String applyTemplate(String template, String name) {
        return template.contains("{name}") ? template.replace("{name}", name) : name + template;
    }

    /** Whether {@code fileName} is itself a ready-marker sentinel (so discovery skips it, not just the data file). */
    private boolean isMarkerFile(String fileName) {
        if (readyMarker == null) return false;
        int i = readyMarker.indexOf("{name}");
        String prefix = i < 0 ? "" : readyMarker.substring(0, i);
        String suffix = i < 0 ? readyMarker : readyMarker.substring(i + "{name}".length());
        return fileName.length() > prefix.length() + suffix.length()
                && fileName.startsWith(prefix) && fileName.endsWith(suffix);
    }

    /** Compile patterns to matchers, defaulting a bare (prefix-less) pattern sensibly (see {@link DiscoveryContext}). */
    private static List<PathMatcher> matchers(List<String> patterns) {
        List<PathMatcher> out = new ArrayList<>(patterns.size());
        for (String raw : patterns) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            String syntaxAndPattern;
            if (p.startsWith("glob:") || p.startsWith("regex:")) syntaxAndPattern = p;       // explicit
            else if (p.indexOf('/') >= 0) syntaxAndPattern = "glob:" + p;                    // path glob
            else syntaxAndPattern = "glob:**/" + p;                                          // filename glob
            out.add(FileSystems.getDefault().getPathMatcher(syntaxAndPattern));
        }
        return out;
    }
}
