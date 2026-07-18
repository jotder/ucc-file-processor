package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.FileSampler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared {@link ConnectionWorkbench} skeleton for the remote protocol connectors (SFTP/FTP/FTPS): the
 * graded checks, jailed relative-path handling, explore mapping and the bounded sample discipline live
 * here; a subclass contributes only the protocol verbs ({@link #connect}, {@link #list},
 * {@link #scratchWriteDelete}, {@link #openFile}, {@link #closeSession}).
 *
 * <p><b>Sample discipline:</b> a file is fetched in full to a throwaway temp dir only when its listed size
 * is known and within {@link #SAMPLE_FETCH_CAP} — anything larger (or size-less) is refused with an honest
 * detail rather than a torn download or a protocol-specific partial-read (FTP data connections cannot be
 * abandoned mid-stream safely). The temp copy is deleted before the result returns.
 */
abstract class AbstractRemoteWorkbench implements ConnectionWorkbench {

    /** Max bytes a sample may fetch (preview-only — a bigger file should be sampled after acquisition). */
    static final long SAMPLE_FETCH_CAP = 8L * 1024 * 1024;

    /** One remote directory entry, protocol-agnostic. */
    record Entry(String name, boolean dir, Long size, Instant mtime) {}

    /** Open the session + authenticate; returns the ok-detail (e.g. {@code "SSH auth ok"}). */
    abstract String connect() throws AcquisitionException;

    /** Children of the base-relative directory {@code relDir} ({@code ""} = the base path itself). */
    abstract List<Entry> list(String relDir) throws AcquisitionException;

    /** Write + delete a scratch file directly under the base path; throws when not permitted. */
    abstract void scratchWriteDelete() throws AcquisitionException;

    /** Open the base-relative file for reading (the connector's own streaming open). */
    abstract InputStream openFile(String relPath, Long size) throws AcquisitionException;

    /** Release the held session. */
    abstract void closeSession() throws AcquisitionException;

    @Override
    public final CheckOutcome check(ProbeCheck check, int sampleLimit) throws AcquisitionException {
        return switch (check) {
            case AUTHENTICATE -> CheckOutcome.ok(check, connect());
            case READ -> { list(""); yield CheckOutcome.ok(check, "base path listable"); }
            case WRITE -> { scratchWriteDelete(); yield CheckOutcome.ok(check, "scratch write + delete ok"); }
            case LIST -> {
                int n = Math.min(list("").size(), Math.max(1, sampleLimit));
                yield CheckOutcome.ok(check, "listed " + n + " entries");
            }
            default -> throw new IllegalStateException("check " + check + " is answered by the prober");
        };
    }

    @Override
    public final List<ResourceNode> explore(String path) throws AcquisitionException {
        String rel = jail(path);
        List<ResourceNode> out = new ArrayList<>();
        for (Entry e : list(rel)) {
            String childPath = rel.isEmpty() ? e.name() : rel + "/" + e.name();
            out.add(new ResourceNode(e.name(), childPath,
                    e.dir() ? ResourceNode.Kind.DIR : ResourceNode.Kind.FILE, e.dir(),
                    e.dir() ? null : e.size(),
                    e.mtime() == null ? null : e.mtime().toString(),
                    null, null));   // remote listings don't carry per-entry permission bits portably
        }
        out.sort(Comparator.comparing(ResourceNode::name));
        return out;
    }

    @Override
    public final SampleResult sample(String path, int limit) throws AcquisitionException {
        String rel = jail(path);
        if (rel.isEmpty()) throw new NoSuchPath("no such file under the connection: " + path);
        int cut = rel.lastIndexOf('/');
        String parent = cut < 0 ? "" : rel.substring(0, cut);
        String name = cut < 0 ? rel : rel.substring(cut + 1);
        Entry entry = list(parent).stream()
                .filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new NoSuchPath("no such file under the connection: " + rel));
        if (entry.dir()) throw new NoSuchPath("'" + rel + "' is a directory, not a file");
        if (entry.size() == null || entry.size() < 0 || entry.size() > SAMPLE_FETCH_CAP)
            return new SampleResult(rel, List.of(), List.of(), true,
                    "file is " + (entry.size() == null || entry.size() < 0 ? "of unknown size" : entry.size() + " bytes")
                            + " — larger than the " + (SAMPLE_FETCH_CAP / (1024 * 1024)) + " MiB preview cap");

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("workbench-sample-");
            Path local = tempDir.resolve(name);
            try (InputStream in = openFile(rel, entry.size())) {
                Files.copy(in, local);
            }
            return FileSampler.sample(local, rel, limit, true);
        } catch (IOException e) {
            throw new AcquisitionException("Cannot fetch '" + rel + "' for sampling: " + e.getMessage(), e);
        } finally {
            if (tempDir != null) deleteQuietly(tempDir);
        }
    }

    @Override
    public final void close() throws AcquisitionException {
        closeSession();
    }

    /**
     * Normalise a user-supplied base-relative path and refuse escapes: absolute paths, drive letters and
     * any {@code ..} that would climb above the base throw {@link PathEscape} (the HTTP edge maps to 403).
     */
    static String jail(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*"))
            throw new PathEscape("path must be relative to the connection base path");
        List<String> segs = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (segs.isEmpty()) throw new PathEscape("path escapes the connection base path");
                segs.remove(segs.size() - 1);
            } else {
                segs.add(seg);
            }
        }
        return String.join("/", segs);
    }

    private static void deleteQuietly(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignore) { /* best effort */ }
            });
        } catch (IOException ignore) { /* best effort */ }
    }
}
