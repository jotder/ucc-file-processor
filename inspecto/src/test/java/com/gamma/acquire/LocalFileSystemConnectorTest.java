package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-A parity + behaviour of the built-in local connector. */
class LocalFileSystemConnectorTest {

    private static LocalFileSystemConnector connector(Path poll) {
        Path p = poll.toAbsolutePath().normalize();
        return new LocalFileSystemConnector(p, p.resolve("errors"), p.resolve("quarantine"));
    }

    private static DiscoveryContext ctx(List<String> includes, List<String> excludes, int depth) {
        return new DiscoveryContext(includes, excludes, depth);
    }

    private static Set<String> rel(List<RemoteFile> files) {
        Set<String> out = new TreeSet<>();
        for (RemoteFile f : files) out.add(f.relativePath());
        return out;
    }

    @Test
    void missingRootDiscoversNothingAndCreatesNothing(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");  // never created
        assertTrue(connector(poll).discover(ctx(List.of("glob:**/*.csv"), List.of(), -1)).isEmpty());
        assertFalse(Files.exists(poll), "discovery must not create the poll root (countPending relies on this)");
    }

    @Test
    void discoversMatchingFilesExcludingErrorsAndQuarantineTrees(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");
        Files.createDirectories(poll.resolve("sub"));
        Files.createDirectories(poll.resolve("errors"));
        Files.createDirectories(poll.resolve("quarantine"));
        Files.writeString(poll.resolve("a.csv"), "x");
        Files.writeString(poll.resolve("sub/b.csv"), "x");
        Files.writeString(poll.resolve("c.txt"), "x");              // wrong extension
        Files.writeString(poll.resolve("errors/d.csv"), "x");       // engine-managed tree
        Files.writeString(poll.resolve("quarantine/e.csv"), "x");   // engine-managed tree

        List<RemoteFile> found = connector(poll).discover(ctx(List.of("glob:**/*.csv"), List.of(), -1));
        assertEquals(Set.of("a.csv", "sub/b.csv"), rel(found));
        assertTrue(found.get(0).isLocal());
        assertFalse(found.get(0).hasSize(), "size is not eagerly stat'd at discovery");
    }

    @Test
    void excludePatternsDropFilesAndBareGlobsMatchByName(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");
        Files.createDirectories(poll);
        Files.writeString(poll.resolve("keep.csv"), "x");
        Files.writeString(poll.resolve("partial.tmp"), "x");
        Files.writeString(poll.resolve("data.partial"), "x");

        // include everything, exclude two bare filename globs
        List<RemoteFile> found = connector(poll)
                .discover(ctx(List.of("glob:**/*"), List.of("*.tmp", "*.partial"), -1));
        assertEquals(Set.of("keep.csv"), rel(found));
    }

    @Test
    void recursiveDepthBoundsTheWalk(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");
        Files.createDirectories(poll.resolve("a/b"));
        Files.writeString(poll.resolve("top.csv"), "x");        // depth 1
        Files.writeString(poll.resolve("a/mid.csv"), "x");      // depth 2
        Files.writeString(poll.resolve("a/b/deep.csv"), "x");   // depth 3

        LocalFileSystemConnector c = connector(poll);
        assertEquals(Set.of("top.csv"),
                rel(c.discover(ctx(List.of("glob:**/*.csv"), List.of(), 1))));
        assertEquals(Set.of("top.csv", "a/mid.csv"),
                rel(c.discover(ctx(List.of("glob:**/*.csv"), List.of(), 2))));
        assertEquals(Set.of("top.csv", "a/mid.csv", "a/b/deep.csv"),
                rel(c.discover(ctx(List.of("glob:**/*.csv"), List.of(), -1))));
    }

    @Test
    void openReadsBytesInPlace(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");
        Files.createDirectories(poll);
        Files.writeString(poll.resolve("f.csv"), "hello");
        LocalFileSystemConnector c = connector(poll);
        RemoteFile f = c.discover(ctx(List.of("glob:**/*.csv"), List.of(), -1)).get(0);
        try (InputStream in = c.open(f)) {
            assertEquals("hello", new String(in.readAllBytes()));
        }
    }

    @Test
    void fetchToWritesOnceAtDestinationWithoutTouchingSource(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");
        Files.createDirectories(poll);
        Files.writeString(poll.resolve("f.csv"), "data");
        LocalFileSystemConnector c = connector(poll);
        RemoteFile f = c.discover(ctx(List.of("glob:**/*.csv"), List.of(), -1)).get(0);

        Path dest = dir.resolve("backup/2026/06/14/f.csv");
        Path written = c.fetchTo(f, dest);
        assertEquals(dest, written);
        assertEquals("data", Files.readString(dest));
        assertTrue(Files.exists(poll.resolve("f.csv")), "fetchTo must not move/delete the source");
    }

    @Test
    void postMoveArchivesOutsideTheScannedTree(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");
        Files.createDirectories(poll);
        Files.writeString(poll.resolve("f.csv"), "data");
        LocalFileSystemConnector c = connector(poll);
        RemoteFile f = c.discover(ctx(List.of("glob:**/*.csv"), List.of(), -1)).get(0);

        c.post(f, PostAction.move("archive/2026"));
        assertFalse(Files.exists(poll.resolve("f.csv")), "source removed after MOVE");
        assertEquals("data", Files.readString(dir.resolve("archive/2026/f.csv")));
    }

    @Test
    void readyMarkerGatesReadinessAndMarkerFilesAreNotDiscovered(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox").toAbsolutePath().normalize();
        Files.createDirectories(poll);
        Files.writeString(poll.resolve("a.csv"), "x");
        LocalFileSystemConnector c = new LocalFileSystemConnector(
                poll, poll.resolve("errors"), poll.resolve("quarantine"), "{name}.done");

        // the data file is discovered; with no sibling sentinel it is NOT_READY (held by the gate)
        List<RemoteFile> found = c.discover(ctx(List.of("glob:**/*.csv"), List.of(), -1));
        assertEquals(Set.of("a.csv"), rel(found));
        RemoteFile a = found.get(0);
        assertEquals(SourceConnector.Readiness.NOT_READY, c.readiness(a));

        // drop the sentinel ⇒ READY; and the sentinel itself is never offered as a candidate
        Files.writeString(poll.resolve("a.csv.done"), "");
        assertEquals(SourceConnector.Readiness.READY, c.readiness(a));
        assertEquals(Set.of("a.csv"), rel(c.discover(ctx(List.of("glob:**/*"), List.of(), -1))),
                "the .done marker is excluded from discovery even under a match-all include");
    }

    @Test
    void withoutReadyMarkerReadinessIsUnknown(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");
        Files.createDirectories(poll);
        Files.writeString(poll.resolve("a.csv"), "x");
        LocalFileSystemConnector c = connector(poll);   // 3-arg ctor ⇒ no ready_marker
        RemoteFile a = c.discover(ctx(List.of("glob:**/*.csv"), List.of(), -1)).get(0);
        assertEquals(SourceConnector.Readiness.UNKNOWN, c.readiness(a),
                "no ready_marker ⇒ defer to the engine's size/mtime stabilization");
    }

    @Test
    void regexIncludeMatchesAlongsideGlob(@TempDir Path dir) throws Exception {
        Path poll = dir.resolve("inbox");
        Files.createDirectories(poll);
        Files.writeString(poll.resolve("cdr_001.dat"), "x");
        Files.writeString(poll.resolve("cdr_002.dat"), "x");
        Files.writeString(poll.resolve("notes.txt"), "x");
        // a regex: include (matched against the full path) is honoured just like a glob: include
        List<RemoteFile> found = connector(poll).discover(ctx(List.of("regex:.*\\.dat"), List.of(), -1));
        assertEquals(Set.of("cdr_001.dat", "cdr_002.dat"), rel(found));
    }

    @Test
    void schemeReadinessAndCapabilities() throws Exception {
        LocalFileSystemConnector c =
                new LocalFileSystemConnector(Path.of("x"), Path.of("x/errors"), Path.of("x/quarantine"));
        assertEquals("local", c.scheme());
        assertTrue(c.capabilities().contains(SourceConnector.Capability.STREAM));
        assertTrue(c.capabilities().contains(SourceConnector.Capability.MOVE));
        assertEquals(SourceConnector.Readiness.UNKNOWN, c.readiness(
                new RemoteFile("f", "f", -1, null, null, null, Path.of("x/f"))));
    }
}
