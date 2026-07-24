package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.FileSampler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared {@link ConnectionWorkbench} skeleton for the REST object-store connectors (S3/GCS/Azure Blob): the
 * graded checks, jailed relative-key handling (reuses {@link AbstractRemoteWorkbench#jail}), a delimiter-based
 * pseudo-directory explore and the bounded sample discipline live here; a subclass contributes only the
 * protocol verbs ({@link #authProbe}, {@link #list}, {@link #openObject}).
 *
 * <p><b>Read-only.</b> The {@code write} probe check is always reported <em>skipped</em> — a workbench must
 * never write a scratch object into someone's production bucket/container to prove write access (the same
 * discipline as {@link DbConnectionWorkbench}).
 *
 * <p><b>Sample discipline:</b> mirrors {@link AbstractRemoteWorkbench} — an object is fetched in full to a
 * throwaway temp dir only when its listed size is known and within {@link #SAMPLE_FETCH_CAP}; the temp copy is
 * deleted before the result returns.
 */
abstract class AbstractObjectStoreWorkbench implements ConnectionWorkbench {

    /** Max bytes a sample may fetch (preview-only — a bigger object should be sampled after acquisition). */
    static final long SAMPLE_FETCH_CAP = 8L * 1024 * 1024;

    /** One entry one level under a prefix: a "directory" is a common prefix (delimiter grouping), never sized. */
    record Entry(String name, boolean dir, Long sizeBytes, String modifiedAt) {}

    /** Verify credentials/reachability against the store (e.g. a cheap bucket/container listing); ok-detail. */
    abstract String authProbe() throws AcquisitionException;

    /** One level of entries directly under the base-relative prefix {@code relPrefix} ({@code ""} = the base path). */
    abstract List<Entry> list(String relPrefix) throws AcquisitionException;

    /** Open the base-relative object for reading (the connector's own streaming open). */
    abstract InputStream openObject(String relKey, Long size) throws AcquisitionException;

    @Override
    public final CheckOutcome check(ProbeCheck check, int sampleLimit) throws AcquisitionException {
        return switch (check) {
            case AUTHENTICATE -> CheckOutcome.ok(check, authProbe());
            case READ -> { list(""); yield CheckOutcome.ok(check, "base path listable"); }
            case WRITE -> CheckOutcome.skipped(check,
                    "object-store workbench is read-only — writes are never probed");
            case LIST -> {
                int n = Math.min(list("").size(), Math.max(1, sampleLimit));
                yield CheckOutcome.ok(check, "listed " + n + " entries");
            }
            default -> throw new IllegalStateException("check " + check + " is answered by the prober");
        };
    }

    @Override
    public final List<ResourceNode> explore(String path) throws AcquisitionException {
        String rel = AbstractRemoteWorkbench.jail(path);
        List<ResourceNode> out = new ArrayList<>();
        for (Entry e : list(rel)) {
            String childPath = rel.isEmpty() ? e.name() : rel + "/" + e.name();
            out.add(new ResourceNode(e.name(), childPath,
                    e.dir() ? ResourceNode.Kind.DIR : ResourceNode.Kind.FILE, e.dir(),
                    e.dir() ? null : e.sizeBytes(), e.modifiedAt(), null, null));
        }
        out.sort(Comparator.comparing(ResourceNode::name));
        return out;
    }

    @Override
    public final SampleResult sample(String path, int limit) throws AcquisitionException {
        String rel = AbstractRemoteWorkbench.jail(path);
        if (rel.isEmpty()) throw new NoSuchPath("no such object under the connection: " + path);
        int cut = rel.lastIndexOf('/');
        String parent = cut < 0 ? "" : rel.substring(0, cut);
        String name = cut < 0 ? rel : rel.substring(cut + 1);
        Entry entry = list(parent).stream()
                .filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new NoSuchPath("no such object under the connection: " + rel));
        if (entry.dir()) throw new NoSuchPath("'" + rel + "' is a prefix, not an object");
        if (entry.sizeBytes() == null || entry.sizeBytes() < 0 || entry.sizeBytes() > SAMPLE_FETCH_CAP)
            return new SampleResult(rel, List.of(), List.of(), true,
                    "object is " + (entry.sizeBytes() == null || entry.sizeBytes() < 0 ? "of unknown size" : entry.sizeBytes() + " bytes")
                            + " — larger than the " + (SAMPLE_FETCH_CAP / (1024 * 1024)) + " MiB preview cap");

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("workbench-sample-");
            Path local = tempDir.resolve(name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name);
            try (InputStream in = openObject(rel, entry.sizeBytes())) {
                Files.copy(in, local);
            }
            return FileSampler.sample(local, rel, limit, true);
        } catch (IOException e) {
            throw new AcquisitionException("Cannot fetch '" + rel + "' for sampling: " + e.getMessage(), e);
        } finally {
            if (tempDir != null) deleteQuietly(tempDir);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignore) { /* best effort */ }
            });
        } catch (IOException ignore) { /* best effort */ }
    }
}
