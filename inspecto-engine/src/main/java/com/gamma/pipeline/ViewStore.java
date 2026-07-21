package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>T32 Phase C — persistence for logical {@code sink.view} definitions.</b> When a flow job produces a
 * {@code sink.view} store (no bytes, §3.1), {@link com.gamma.pipeline.exec.PipelineJobRunner} records a
 * {@link ViewDefinition} here as {@code <root>/<store>_view.toon}. Mirrors {@link PipelineStore} — store name
 * sanitised, path-jailed, written atomically (temp + atomic move) — so the binding side (a KPI / report /
 * alert API) can discover a view and the flow that concretises it. Pure file I/O over a root; unit-tests directly.
 */
@PublicApi(since = "4.3.0")
public final class ViewStore {

    private static final Logger log = LoggerFactory.getLogger(ViewStore.class);
    private static final String SUFFIX = "_view.toon";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Path viewsRoot;

    public ViewStore(Path viewsRoot) {
        this.viewsRoot = Objects.requireNonNull(viewsRoot, "viewsRoot").normalize();
    }

    public Path root() {
        return viewsRoot;
    }

    /** Every registered view definition on disk (re-scans), in filename order; an unparseable file is skipped. */
    public List<ViewDefinition> list() {
        List<ViewDefinition> out = new ArrayList<>();
        if (!Files.isDirectory(viewsRoot)) return out;
        try (Stream<Path> files = Files.list(viewsRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .forEach(p -> {
                        try {
                            out.add(ViewDefinition.fromMap(ToonHelper.load(p.toString())));
                        } catch (Exception e) {
                            log.warn("Could not load view definition {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Cannot scan views dir {}: {}", viewsRoot, e.getMessage());
        }
        return out;
    }

    /** One view definition by its store name, if present. */
    public Optional<ViewDefinition> get(String store) {
        Path file = fileFor(store);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(ViewDefinition.fromMap(ToonHelper.load(file.toString())));
        } catch (Exception e) {
            log.warn("Could not load view definition {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean exists(String store) {
        return Files.isRegularFile(fileFor(store));
    }

    /** Write (create or replace) the definition at {@code <root>/<store>_view.toon}, atomically. */
    public ViewDefinition write(ViewDefinition def) throws IOException {
        Path file = fileFor(def.store());
        byte[] bytes = ConfigCodec.toToon(def.toMap()).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file, bytes, ".view-");
        return def;
    }

    /** Delete a view definition by store name; returns whether a file was removed. */
    public boolean delete(String store) throws IOException {
        return Files.deleteIfExists(fileFor(store));
    }

    private Path fileFor(String store) {
        if (store == null) throw new IllegalArgumentException("view store name is required");
        String s = store.trim();
        if (s.contains("..") || !SAFE_ID.matcher(s).matches())
            throw new IllegalArgumentException(
                    "unsafe view store name '" + store + "' (allowed: letters, digits, '.', '_', '-')");
        Path target = viewsRoot.resolve(s + SUFFIX).normalize();
        if (!target.startsWith(viewsRoot))
            throw new IllegalArgumentException("resolved path escapes the views root");
        return target;
    }
}
