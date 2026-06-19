package com.gamma.flow;

import com.gamma.api.PublicApi;
import com.gamma.config.io.ConfigCodec;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Persistence for authored flows (doc §7.1 / §14 T19): create / replace / delete / list {@code *_flow.toon}
 * files under a root, each round-tripped through {@link FlowCodec}. Mirrors {@link ComponentStore} — id
 * sanitised, path-jailed, written atomically (temp + atomic move). A flow is addressed by its {@code id},
 * which is also its graph {@link FlowGraph#name() name} and filename stem.
 *
 * <p>These are the <b>build side</b> of the NiFi UX: authored flows are validated ({@link FlowValidator}) and
 * stored; wiring them into the live executor is a separate concern (the legacy poll loop still runs the
 * lifted {@code *_pipeline.toon}). Pure file I/O over a root — no HTTP — so it unit-tests directly.
 */
@PublicApi(since = "4.3.0")
public final class FlowStore {

    private static final Logger log = LoggerFactory.getLogger(FlowStore.class);
    private static final String TOON = ".toon";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Path flowsRoot;

    public FlowStore(Path flowsRoot) {
        this.flowsRoot = Objects.requireNonNull(flowsRoot, "flowsRoot").normalize();
    }

    public Path root() {
        return flowsRoot;
    }

    /** Every authored flow on disk (re-scans), in filename order; an unparseable file is warned and skipped. */
    public List<FlowGraph> list() {
        List<FlowGraph> out = new ArrayList<>();
        if (!Files.isDirectory(flowsRoot)) return out;
        try (Stream<Path> files = Files.list(flowsRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(TOON))
                    .sorted()
                    .forEach(p -> {
                        try {
                            out.add(FlowCodec.fromMap(ToonHelper.load(p.toString())));
                        } catch (Exception e) {
                            log.warn("Could not load authored flow {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Cannot scan flows dir {}: {}", flowsRoot, e.getMessage());
        }
        return out;
    }

    /** One authored flow by id, if present. An unsafe/unresolvable id is treated as "not present" (empty). */
    public Optional<FlowGraph> get(String id) {
        Path file = fileForOrNull(id);
        if (file == null || !Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(FlowCodec.fromMap(ToonHelper.load(file.toString())));
        } catch (Exception e) {
            log.warn("Could not load authored flow {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** Whether a flow is stored under {@code id}; an unsafe/unresolvable id is "not present" (false). */
    public boolean exists(String id) {
        Path file = fileForOrNull(id);
        return file != null && Files.isRegularFile(file);
    }

    /** Write (create or replace) the flow at {@code <root>/<id>.toon}, atomically. Returns the written graph. */
    public FlowGraph write(String id, FlowGraph g) throws IOException {
        Path file = fileFor(id);
        byte[] bytes = ConfigCodec.toToon(FlowCodec.toMap(g)).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".flow-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return g;
    }

    /** Delete the flow's backing file; returns whether a file was removed. */
    public boolean delete(String id) throws IOException {
        return Files.deleteIfExists(fileFor(id));
    }

    /** Resolve {@code id} to its backing file for a <b>read</b>, or {@code null} if the id is unsafe/unresolvable. */
    private Path fileForOrNull(String id) {
        try {
            return fileFor(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Path fileFor(String id) {
        if (id == null) throw new IllegalArgumentException("flow id is required");
        String s = id.trim();
        if (s.contains("..") || !SAFE_ID.matcher(s).matches())
            throw new IllegalArgumentException(
                    "unsafe flow id '" + id + "' (allowed: letters, digits, '.', '_', '-')");
        Path target = flowsRoot.resolve(s + TOON).normalize();
        if (!target.startsWith(flowsRoot))
            throw new IllegalArgumentException("resolved path escapes the flows root");
        return target;
    }
}
