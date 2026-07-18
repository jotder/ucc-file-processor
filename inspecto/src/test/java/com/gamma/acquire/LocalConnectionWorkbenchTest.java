package com.gamma.acquire;

import com.gamma.acquire.ConnectionWorkbench.ProbeCheck;
import com.gamma.acquire.ConnectionWorkbench.ResourceNode;
import com.gamma.acquire.ConnectionWorkbench.SampleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The built-in local {@link ConnectionWorkbench}: graded probe checks, jailed explore, DuckDB-backed sample. */
class LocalConnectionWorkbenchTest {

    private ConnectionProfile local(Path root) {
        return ConnectionProfile.fromMap(Map.of("id", "L", "connector", "local", "base_path", root.toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void probeRunsAllChecksAgainstARealDirectory(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.csv"), "x\n1\n");
        Map<String, Object> r = ConnectionProber.probe(local(root), EnumSet.noneOf(ProbeCheck.class), 25);

        assertEquals(true, r.get("ok"), r.toString());
        assertEquals("local", r.get("endpoint"));
        List<Map<String, Object>> checks = (List<Map<String, Object>>) r.get("checks");
        assertEquals(5, checks.size(), "all five checks run when none are requested explicitly");
        Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
        checks.forEach(c -> byName.put((String) c.get("check"), c));
        assertEquals(true, byName.get("reachability").get("ok"));
        assertEquals(true, byName.get("authenticate").get("skipped"), "local has no authentication — honest skip");
        assertEquals(true, byName.get("read").get("ok"));
        assertEquals(true, byName.get("write").get("ok"), "scratch write + delete");
        assertEquals(true, byName.get("list").get("ok"));
        try (var s = Files.list(root)) {
            assertEquals(1, s.count(), "the write check left no scratch file behind");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void probeOfAnUnsupportedRemoteSchemeSkipsGradedChecks() throws Exception {
        // A local-loopback listening socket so reachability is genuinely true; no sftp factory on this classpath.
        try (java.net.ServerSocket listening = new java.net.ServerSocket(0)) {
            ConnectionProfile p = ConnectionProfile.fromMap(Map.of(
                    "id", "R", "connector", "sftp", "host", "127.0.0.1",
                    "port", String.valueOf(listening.getLocalPort()),
                    "options", Map.of("test_timeout_ms", "500")));
            Map<String, Object> r = ConnectionProber.probe(p, EnumSet.noneOf(ProbeCheck.class), 25);
            List<Map<String, Object>> checks = (List<Map<String, Object>>) r.get("checks");
            assertEquals(true, checks.get(0).get("ok"), "reachability is answered generically");
            for (Map<String, Object> c : checks.subList(1, checks.size())) {
                assertEquals(true, c.get("skipped"), c + " must be skipped, not fabricated");
                assertTrue(String.valueOf(c.get("detail")).contains("not supported"), c.toString());
            }
            assertEquals(true, r.get("ok"), "skipped checks don't fail the probe");
        }
    }

    @Test
    void exploreListsChildrenAndJailsThePath(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("inbox"));
        Files.writeString(root.resolve("readme.txt"), "hello");
        try (ConnectionWorkbench wb = ConnectionProber.workbenchFor(local(root))) {
            List<ResourceNode> nodes = wb.explore("");
            assertEquals(2, nodes.size());
            ResourceNode dir = nodes.stream().filter(n -> n.name().equals("inbox")).findFirst().orElseThrow();
            assertEquals(ResourceNode.Kind.DIR, dir.kind());
            assertTrue(dir.hasChildren());
            ResourceNode file = nodes.stream().filter(n -> n.name().equals("readme.txt")).findFirst().orElseThrow();
            assertEquals(ResourceNode.Kind.FILE, file.kind());
            assertEquals(5L, file.sizeBytes());
            assertNotNull(file.modifiedAt());

            assertTrue(wb.explore("inbox").isEmpty(), "empty subdir lists as empty, not an error");
            assertThrows(ConnectionWorkbench.PathEscape.class, () -> wb.explore("../.."), "jail");
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.explore("nope"));
        }
    }

    @Test
    void sampleParsesCsvWithTheProductionReader(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("feed.csv"), "id,name\n1,a\n2,b\n3,c\n");
        try (ConnectionWorkbench wb = ConnectionProber.workbenchFor(local(root))) {
            SampleResult s = wb.sample("feed.csv", 2);
            assertEquals(List.of("id", "name"), s.columns());
            assertEquals(2, s.rows().size());
            assertEquals("a", s.rows().get(0).get("name"));
            assertTrue(s.truncated(), "3 data rows, limit 2 ⇒ truncated");

            SampleResult all = wb.sample("feed.csv", 50);
            assertEquals(3, all.rows().size());
            assertFalse(all.truncated());
        }
    }

    @Test
    void sampleOfANonTabularFileFallsBackToRawLines(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("app.log"), "line one\nline two\n");
        try (ConnectionWorkbench wb = ConnectionProber.workbenchFor(local(root))) {
            SampleResult s = wb.sample("app.log", 50);
            assertEquals(List.of("line"), s.columns());
            assertEquals("line one", s.rows().get(0).get("line"));
            assertFalse(s.truncated());
        }
    }
}
