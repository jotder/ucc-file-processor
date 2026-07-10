package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The write side of the component registry (doc §7.1 / §14 T19): create / replace / delete the reusable
 * {@code registry/<typeDir>/<id>.toon} components that {@link ComponentRegistry} reads. Generalises the
 * connection CRUD pattern ({@code ControlApi.persistConnection}) — id-sanitised, path-jailed under the
 * registry root, written atomically (temp + atomic move) — to every non-secret component type
 * ({@code grammar} / {@code schema} / {@code transform} / {@code sink}). {@code connection} keeps its own
 * secret-masking CRUD and is intentionally excluded here.
 *
 * <p>Reads re-scan the root each call (small dirs; always current). The {@code id} is stamped as the
 * content's {@code name} on write, so a component's in-file identity, its URL id, and its filename stem
 * all agree (and a later read resolves it by {@code type/id}). Pure file I/O over a root — no HTTP — so it
 * unit-tests directly.
 */
@PublicApi(since = "4.3.0")
public final class ComponentStore {

    /**
     * Component types managed here. {@code connection} is excluded — it has its own secret-aware CRUD.
     * Widened (W3, 2026-07-06) with the Studio metadata kinds {@code dataset}/{@code widget}/{@code dashboard}
     * so they persist for real instead of being Angular-mock-only (unblocks the backend-backlog items that
     * all waited on this one set); each has a matching registry dir in {@link ComponentRegistry#TYPE_BY_DIR}.
     */
    public static final Set<String> WRITABLE_TYPES =
            Set.of("grammar", "schema", "transform", "sink", "dataset", "widget", "dashboard", "query",
                    "expectation", "requirement",
                    // INV-1/INV-2 saved investigation views (2026-07-08): the UI's SavedViewStore already
                    // speaks the /components contract — widening here is what moves them off the mock store.
                    "link-analysis-view", "geo-map-view");

    private static final String TOON = ".toon";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * MET-5 version history: prior copies of an overwritten component live under a {@code .history/}
     * sub-directory of the type dir (e.g. {@code grammars/.history/pipe.v3.toon}). It is a
     * <b>sub-directory, not a sibling</b> {@code .toon}, on purpose — {@link ComponentRegistry#scan}
     * reads a component's in-file {@code name}, so a versioned copy sitting beside the live file would
     * register as a duplicate and shadow it. {@code scan} only lists the known type dirs' regular files,
     * so {@code .history/} is invisible to it. Keep the newest {@value #HISTORY_KEEP_DEFAULT}
     * (override with {@code -Dcomponents.history.keep}).
     */
    private static final String HISTORY_DIR = ".history";
    private static final int HISTORY_KEEP_DEFAULT = 10;
    private static final int HISTORY_KEEP =
            Math.max(1, Integer.getInteger("components.history.keep", HISTORY_KEEP_DEFAULT));

    private final Path registryRoot;

    /** @param registryRoot the parent of {@code grammars/}, {@code schemas/}, {@code transforms/}, {@code sinks/}. */
    public ComponentStore(Path registryRoot) {
        this.registryRoot = Objects.requireNonNull(registryRoot, "registryRoot").normalize();
    }

    public Path root() {
        return registryRoot;
    }

    /** Components of {@code type} currently on disk (re-scans), in scan order. */
    public List<ComponentRegistry.Component> list(String type) {
        validateType(type);
        return ComponentRegistry.scan(registryRoot).ofType(type);
    }

    /** One component by {@code type}/{@code id} (its in-file identity), if present. */
    public Optional<ComponentRegistry.Component> get(String type, String id) {
        validateType(type);
        return ComponentRegistry.scan(registryRoot).resolve(type + "/" + validId(id));
    }

    public boolean exists(String type, String id) {
        return get(type, id).isPresent();
    }

    /**
     * Write (create or replace) a component at {@code registry/<typeDir>/<id>.toon}, atomically. {@code id}
     * is stamped as the content's {@code name} so the in-file identity matches the URL id. Returns the
     * written component (its parsed content as persisted). Archives the outgoing copy (MET-5).
     */
    public ComponentRegistry.Component write(String type, String id, Map<String, Object> content) throws IOException {
        return write(type, id, content, true);
    }

    /**
     * {@link #write(String, String, Map)} with an explicit archive switch. Pass {@code archive=false} for
     * <b>result-stamp</b> writes (e.g. an Expectation persisting {@code lastResult} after a run-check) —
     * version history tracks authoring edits, and a stamp per evaluation would churn real edits out of the
     * keep-N window.
     */
    public ComponentRegistry.Component write(String type, String id, Map<String, Object> content, boolean archive)
            throws IOException {
        validateType(type);
        String name = validId(id);
        if (content == null) throw new IllegalArgumentException("component content is required");
        Path file = fileFor(type, name);
        Map<String, Object> doc = new LinkedHashMap<>(content);
        doc.put("name", name);   // canonicalise: in-file identity == URL id == filename stem
        if (archive) archivePrevious(type, name, file);   // MET-5: snapshot the outgoing copy first
        byte[] bytes = ConfigCodec.toToon(doc).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file, bytes, ".comp-");
        return new ComponentRegistry.Component(type, name, file, doc);
    }

    /** Delete a component's backing file (resolved by in-file identity). Returns whether a file was removed. */
    public boolean delete(String type, String id) throws IOException {
        Optional<ComponentRegistry.Component> c = get(type, id);
        if (c.isEmpty()) return false;
        boolean removed = Files.deleteIfExists(c.get().path());
        purgeHistory(type, validId(id));   // MET-5: delete means gone, history included (restore is for edits)
        return removed;
    }

    // ── MET-5 version history ───────────────────────────────────────────────────

    /** One archived prior copy of a component: its version number, when it was saved, and its content. */
    public record ComponentVersion(int version, Instant savedAt, Map<String, Object> content) {}

    /** Prior versions of {@code type}/{@code id}, newest first (empty when none / no history dir). */
    public List<ComponentVersion> versions(String type, String id) {
        validateType(type);
        String name = validId(id);
        Path dir = historyDir(type);
        List<ComponentVersion> out = new ArrayList<>();
        for (Path p : historyFiles(dir, name)) {
            int v = versionOf(p, name);
            if (v < 0) continue;
            try {
                Map<String, Object> content = ConfigCodec.toMap(Files.readString(p, StandardCharsets.UTF_8));
                out.add(new ComponentVersion(v, Files.getLastModifiedTime(p).toInstant(), content));
            } catch (IOException | RuntimeException ignored) {
                // a corrupt/half archived copy is skipped, never blocking the rest of the history
            }
        }
        out.sort(Comparator.comparingInt(ComponentVersion::version).reversed());
        return out;
    }

    /** The content of one archived version of {@code type}/{@code id}, if that version exists. */
    public Optional<Map<String, Object>> versionContent(String type, String id, int version) {
        return versions(type, id).stream().filter(v -> v.version() == version).findFirst()
                .map(ComponentVersion::content);
    }

    /**
     * Copy the current file (if any) into {@code .history/<id>.v<next>.toon}, preserving its saved time,
     * then prune to the newest {@link #HISTORY_KEEP}. A missing current file (a create) is a no-op.
     */
    private void archivePrevious(String type, String id, Path current) throws IOException {
        if (!Files.exists(current)) return;
        Path dir = historyDir(type);
        int next = maxVersion(dir, id) + 1;
        Path archived = dir.resolve(id + ".v" + next + TOON);
        FileTime saved = Files.getLastModifiedTime(current);
        AtomicFiles.write(archived, Files.readAllBytes(current), ".hist-");
        try {
            Files.setLastModifiedTime(archived, saved);   // savedAt = when this copy was the live one
        } catch (IOException ignored) {
            // best-effort; falls back to the archive time if the fs rejects the stamp
        }
        prune(dir, id);
    }

    private void prune(Path dir, String id) throws IOException {
        List<Path> files = historyFiles(dir, id);
        files.sort(Comparator.comparingInt((Path p) -> versionOf(p, id)).reversed());
        for (int i = HISTORY_KEEP; i < files.size(); i++) Files.deleteIfExists(files.get(i));
    }

    private void purgeHistory(String type, String id) throws IOException {
        for (Path p : historyFiles(historyDir(type), id)) Files.deleteIfExists(p);
    }

    private Path historyDir(String type) {
        String dir = ComponentRegistry.dirForType(type)
                .orElseThrow(() -> new IllegalArgumentException("no registry dir for type '" + type + "'"));
        return registryRoot.resolve(dir).resolve(HISTORY_DIR);
    }

    /** The {@code <id>.v<N>.toon} archive files for {@code id} in {@code dir} (empty if the dir is absent). */
    private static List<Path> historyFiles(Path dir, String id) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> versionOf(p, id) >= 0)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int maxVersion(Path dir, String id) {
        return historyFiles(dir, id).stream().mapToInt(p -> versionOf(p, id)).max().orElse(0);
    }

    /** Parse N from a {@code <id>.v<N>.toon} filename, or {@code -1} if it isn't one (digits only). */
    private static int versionOf(Path p, String id) {
        String f = p.getFileName().toString();
        String prefix = id + ".v";
        if (!f.startsWith(prefix) || !f.endsWith(TOON)) return -1;
        String mid = f.substring(prefix.length(), f.length() - TOON.length());
        if (mid.isEmpty() || !mid.chars().allMatch(Character::isDigit)) return -1;
        try {
            return Integer.parseInt(mid);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private void validateType(String type) {
        if (!WRITABLE_TYPES.contains(type))
            throw new IllegalArgumentException(
                    "unknown component type '" + type + "' (expected one of " + WRITABLE_TYPES + ")");
    }

    private static String validId(String id) {
        if (id == null) throw new IllegalArgumentException("component id is required");
        String s = id.trim();
        if (s.contains("..") || !SAFE_ID.matcher(s).matches())
            throw new IllegalArgumentException(
                    "unsafe component id '" + id + "' (allowed: letters, digits, '.', '_', '-')");
        return s;
    }

    private Path fileFor(String type, String id) {
        String dir = ComponentRegistry.dirForType(type)
                .orElseThrow(() -> new IllegalArgumentException("no registry dir for type '" + type + "'"));
        Path target = registryRoot.resolve(dir).resolve(id + TOON).normalize();
        if (!target.startsWith(registryRoot))
            throw new IllegalArgumentException("resolved path escapes the registry root");
        return target;
    }
}
