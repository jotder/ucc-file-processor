package com.gamma.acquire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The built-in {@link ConnectionWorkbench} for {@code local} connection profiles: probes, explores and
 * samples the directory tree under the profile's {@code base_path}. Every user-supplied {@code path} is
 * jailed under that root ({@code resolve().normalize().startsWith}) — an escape throws
 * {@link ConnectionWorkbench.PathEscape}, which the HTTP edge maps to 403.
 */
public final class LocalConnectionWorkbench implements ConnectionWorkbench {

    private final Path root;   // null when the profile has no base_path — every verb refuses honestly

    public LocalConnectionWorkbench(ConnectionProfile profile) {
        String bp = profile.basePath();
        this.root = (bp == null || bp.isBlank()) ? null : Paths.get(bp.trim()).toAbsolutePath().normalize();
    }

    @Override
    public CheckOutcome check(ProbeCheck check, int sampleLimit) {
        if (root == null && check != ProbeCheck.AUTHENTICATE)
            return CheckOutcome.fail(check, "connection has no base_path configured");
        return switch (check) {
            case AUTHENTICATE -> CheckOutcome.skipped(check, "local filesystem — no authentication");
            case READ -> Files.isDirectory(root) && Files.isReadable(root)
                    ? CheckOutcome.ok(check, "base path readable")
                    : CheckOutcome.fail(check, "base path missing or unreadable: " + root);
            case WRITE -> scratchWrite();
            case LIST -> list(sampleLimit);
            default -> throw new IllegalStateException("check " + check + " is answered by the prober");
        };
    }

    /** Write + delete a scratch file under the root — proves real write permission, leaves nothing behind. */
    private CheckOutcome scratchWrite() {
        if (!Files.isDirectory(root)) return CheckOutcome.fail(ProbeCheck.WRITE, "base path missing: " + root);
        Path scratch = root.resolve(".inspecto-probe-" + System.nanoTime() + ".tmp");
        try {
            Files.writeString(scratch, "probe");
            Files.delete(scratch);
            return CheckOutcome.ok(ProbeCheck.WRITE, "scratch write + delete ok");
        } catch (IOException e) {
            try { Files.deleteIfExists(scratch); } catch (IOException ignore) { /* best effort */ }
            return CheckOutcome.fail(ProbeCheck.WRITE, "not writable: " + e.getMessage());
        }
    }

    private CheckOutcome list(int sampleLimit) {
        if (!Files.isDirectory(root)) return CheckOutcome.fail(ProbeCheck.LIST, "base path missing: " + root);
        try (Stream<Path> s = Files.list(root)) {
            long n = s.limit(Math.max(1, sampleLimit)).count();
            return CheckOutcome.ok(ProbeCheck.LIST, "listed " + n + " entries");
        } catch (IOException e) {
            return CheckOutcome.fail(ProbeCheck.LIST, "cannot list base path: " + e.getMessage());
        }
    }

    @Override
    public List<ResourceNode> explore(String path) throws AcquisitionException {
        Path dir = jail(path);
        if (!Files.isDirectory(dir)) throw new NoSuchPath("no such directory under the connection: " + norm(path));
        List<ResourceNode> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path child : s.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList())
                out.add(node(child));
        } catch (IOException e) {
            throw new AcquisitionException("Cannot list " + norm(path), e);
        }
        return out;
    }

    @Override
    public SampleResult sample(String path, int limit) throws AcquisitionException {
        Path file = jail(path);
        if (!Files.isRegularFile(file)) throw new NoSuchPath("no such file under the connection: " + norm(path));
        return FileSampler.sample(file, norm(path), limit, true);
    }

    private ResourceNode node(Path p) {
        boolean dir = Files.isDirectory(p);
        Long size = null;
        String modified = null;
        if (!dir) {
            try { size = Files.size(p); } catch (IOException ignore) { /* stat best-effort */ }
            try { modified = Files.getLastModifiedTime(p).toInstant().toString(); } catch (IOException ignore) { }
        }
        return new ResourceNode(p.getFileName().toString(),
                root.relativize(p).toString().replace('\\', '/'),
                dir ? ResourceNode.Kind.DIR : ResourceNode.Kind.FILE,
                dir, size, modified, Files.isReadable(p), Files.isWritable(p));
    }

    /** Resolve a connector-relative path under the root; escaping it is a {@link PathEscape} (HTTP 403). */
    private Path jail(String path) {
        if (root == null) throw new IllegalArgumentException("connection has no base_path configured");
        Path resolved = root.resolve(norm(path)).normalize();
        if (!resolved.startsWith(root)) throw new PathEscape("path escapes the connection base path");
        return resolved;
    }

    private static String norm(String path) {
        return path == null ? "" : path.trim();
    }
}
