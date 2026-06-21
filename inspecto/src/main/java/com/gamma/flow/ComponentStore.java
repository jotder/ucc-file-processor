package com.gamma.flow;

import com.gamma.api.PublicApi;
import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** Component types managed here. {@code connection} is excluded — it has its own secret-aware CRUD. */
    public static final Set<String> WRITABLE_TYPES = Set.of("grammar", "schema", "transform", "sink");

    private static final String TOON = ".toon";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

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
     * written component (its parsed content as persisted).
     */
    public ComponentRegistry.Component write(String type, String id, Map<String, Object> content) throws IOException {
        validateType(type);
        String name = validId(id);
        if (content == null) throw new IllegalArgumentException("component content is required");
        Path file = fileFor(type, name);
        Map<String, Object> doc = new LinkedHashMap<>(content);
        doc.put("name", name);   // canonicalise: in-file identity == URL id == filename stem
        byte[] bytes = ConfigCodec.toToon(doc).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file, bytes, ".comp-");
        return new ComponentRegistry.Component(type, name, file, doc);
    }

    /** Delete a component's backing file (resolved by in-file identity). Returns whether a file was removed. */
    public boolean delete(String type, String id) throws IOException {
        Optional<ComponentRegistry.Component> c = get(type, id);
        return c.isPresent() && Files.deleteIfExists(c.get().path());
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
