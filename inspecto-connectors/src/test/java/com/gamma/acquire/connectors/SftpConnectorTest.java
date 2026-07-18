package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.ConnectionWorkbench.ProbeCheck;
import com.gamma.acquire.ConnectionWorkbench.ResourceNode;
import com.gamma.acquire.ConnectionWorkbench.SampleResult;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.IntegrityChecker;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.CollectorConnector;
import net.schmizz.sshj.common.SecurityUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase E — exercises {@link SftpConnector} (sshj client) end-to-end against an in-process Apache MINA SSHD
 * SFTP server rooted at a temp directory.
 */
class SftpConnectorTest {

    private SshServer sshd;
    private Path serverRoot;
    private int port;
    private KeyPair hostKey;   // the server's host key — generated here so the test knows its fingerprint

    @BeforeEach
    void startServer(@TempDir Path tmp) throws Exception {
        serverRoot = Files.createDirectories(tmp.resolve("sftproot"));
        hostKey = KeyPairGenerator.getInstance("RSA").genKeyPair();
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        sshd.setPasswordAuthenticator((u, p, s) -> "user".equals(u) && "pw".equals(p));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
        sshd.start();
        port = sshd.getPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (sshd != null) sshd.stop(true);
    }

    /** The server host key's fingerprint, in the same form the sshj client verifier compares against. */
    private String serverFingerprint() {
        return SecurityUtils.getFingerprint(hostKey.getPublic());
    }

    private ConnectionProfile profile() {
        return profile(Map.of());
    }

    private ConnectionProfile profile(Map<String, String> options) {
        return new ConnectionProfile("test-sftp", "sftp", "127.0.0.1", port, null, "/",
                "user", "pw", options, null);
    }

    private SftpConnector connector() {
        return new SftpConnector(profile(), null);
    }

    private SftpConnector connector(Map<String, String> options) {
        return new SftpConnector(profile(options), null);
    }

    private static DiscoveryContext anyCsv() {
        return new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED);
    }

    // ── host-key pinning (security hardening) ────────────────────────────────────

    @Test
    void pinnedHostKeyFingerprintAllowsConnect() throws Exception {
        Files.writeString(serverRoot.resolve("a.csv"), "ID\n1\n");
        try (CollectorConnector c = connector(Map.of("host_key", serverFingerprint()))) {
            assertEquals(1, c.discover(anyCsv()).size(), "the matching pinned fingerprint connects");
        }
    }

    @Test
    void wrongHostKeyFingerprintRejectsConnect() {
        try (CollectorConnector c = connector(Map.of(
                "host_key", "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"))) {
            assertThrows(AcquisitionException.class, () -> c.discover(anyCsv()),
                    "a host key that doesn't match the pin is refused (MITM defence)");
        } catch (AcquisitionException closeFailure) {
            // close() of a never-connected connector is a no-op; nothing to assert here
        }
    }

    @Test
    void strictHostKeyWithoutAnyPinRefusesConnect() {
        try (CollectorConnector c = connector(Map.of("strict_host_key", "true"))) {
            assertThrows(AcquisitionException.class, () -> c.discover(anyCsv()),
                    "strict mode refuses accept-on-connect when no host_key/known_hosts is configured");
        } catch (AcquisitionException closeFailure) {
            // no-op
        }
    }

    @Test
    void noPinStillConnectsAcceptOnConnect() throws Exception {
        Files.writeString(serverRoot.resolve("a.csv"), "ID\n1\n");
        try (CollectorConnector c = connector()) {   // empty options ⇒ legacy accept-on-connect
            assertEquals(1, c.discover(anyCsv()).size(), "unpinned profiles keep working (backward compatible)");
        }
    }

    @Test
    void discoversMatchingFilesRecursivelyWithListingMetadata() throws Exception {
        Files.writeString(serverRoot.resolve("a.csv"), "ID,AMT\n1,2\n");
        Files.createDirectories(serverRoot.resolve("sub"));
        Files.writeString(serverRoot.resolve("sub/b.csv"), "ID,AMT\n3,4\n");
        Files.writeString(serverRoot.resolve("notes.txt"), "ignore me");

        try (CollectorConnector c = connector()) {
            List<RemoteFile> found = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED));
            assertEquals(2, found.size(), "two CSVs, the .txt excluded by the include glob");
            RemoteFile a = found.stream().filter(f -> f.relativePath().equals("a.csv")).findFirst().orElseThrow();
            assertTrue(a.hasSize() && a.size() > 0, "SFTP listing carries the size");
            assertNotNull(a.lastModified(), "SFTP listing carries the mtime");
            assertTrue(found.stream().anyMatch(f -> f.relativePath().equals("sub/b.csv")), "recurses into subdirs");
        }
    }

    @Test
    void respectsDepthLimit() throws Exception {
        Files.writeString(serverRoot.resolve("top.csv"), "x\n");
        Files.createDirectories(serverRoot.resolve("d"));
        Files.writeString(serverRoot.resolve("d/deep.csv"), "y\n");

        try (CollectorConnector c = connector()) {
            List<RemoteFile> found = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), 1));
            assertEquals(List.of("top.csv"), found.stream().map(RemoteFile::relativePath).toList());
        }
    }

    @Test
    void fetchToWritesBytesAndIntegrityPasses(@TempDir Path local) throws Exception {
        String body = "ID,AMT\n1,9.99\n2,8.88\n";
        Files.writeString(serverRoot.resolve("feed.csv"), body);

        try (CollectorConnector c = connector()) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            Path dest = local.resolve("feed.csv");
            Path got = c.fetchTo(rf, dest);
            assertEquals(body, Files.readString(got, StandardCharsets.UTF_8));
            assertTrue(IntegrityChecker.verify(rf, got, "SHA-256").ok(), "size matches the listing → integrity passes");
        }
    }

    @Test
    void openStreamsWithoutALocalCopy() throws Exception {
        String body = "a,b,c\n";
        Files.writeString(serverRoot.resolve("s.csv"), body);
        try (CollectorConnector c = connector()) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            try (InputStream in = c.open(rf)) {
                assertEquals(body, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void fetchToResumesAPartialDownload(@TempDir Path local) throws Exception {
        String body = "0123456789ABCDEF".repeat(64);   // 1024 bytes
        Files.writeString(serverRoot.resolve("big.bin"), body);

        Path dest = local.resolve("big.bin");
        Files.writeString(dest, body.substring(0, 400));   // pretend a prior run got the first 400 bytes

        try (CollectorConnector c = connector()) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of("*.bin"), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            Path got = c.fetchTo(rf, dest);
            assertEquals(body, Files.readString(got, StandardCharsets.UTF_8), "resume appends the remaining bytes exactly");
        }
    }

    @Test
    void endToEndSftpSourceIsIngestedAndDedupedOnReRun(@TempDir Path dir) throws Exception {
        // Two CSVs on the SFTP server; a pipeline with source.connector=sftp pulls them into the local
        // staging tree (poll) and the normal batch path ingests them. A second run re-lists but, finding the
        // markers for the already-staged files, fetches and processes nothing.
        Files.writeString(serverRoot.resolve("20200403_feed.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n");
        Files.writeString(serverRoot.resolve("20200404_feed.csv"), "ID,AMT,EVENT_DATE\nr2,2.0,2020-04-04\n");

        ConnectionRegistry.register(profile());
        try {
            com.gamma.etl.PipelineConfig cfg = com.gamma.etl.PipelineConfig.load(writeSftpPipeline(dir).toString());

            com.gamma.inspector.CollectorProcessor.run(cfg);
            String afterFirst = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
            assertEquals(2, afterFirst.split("\n").length, "header + 1 batch row from the 2 fetched files");
            try (var w = Files.walk(Path.of(cfg.dirs().database()))) {
                assertTrue(w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv")),
                        "the fetched SFTP files were ingested to an output");
            }

            com.gamma.inspector.CollectorProcessor.run(cfg);
            assertEquals(afterFirst, Files.readString(Path.of(cfg.dirs().batchesFilePath())),
                    "re-run finds the markers → fetches/processes nothing new");
        } finally {
            ConnectionRegistry.remove("test-sftp");
        }
    }

    // ── Phase F: source-side post-actions ────────────────────────────────────────

    @Test
    void postDeleteRemovesTheSourceFile() throws Exception {
        Files.writeString(serverRoot.resolve("d.csv"), "x\n");
        try (CollectorConnector c = connector()) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            c.post(rf, PostAction.RETAIN);
            assertTrue(Files.exists(serverRoot.resolve("d.csv")), "RETAIN leaves it");
            c.post(rf, new PostAction(PostAction.Kind.DELETE, null, java.util.Map.of()));
            assertFalse(Files.exists(serverRoot.resolve("d.csv")), "DELETE removes the source file");
        }
    }

    @Test
    void postMoveRelocatesIntoTheArchiveTree() throws Exception {
        Files.writeString(serverRoot.resolve("m.csv"), "y\n");
        try (CollectorConnector c = connector()) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            c.post(rf, PostAction.move("archive/2026/06/14"));
            assertFalse(Files.exists(serverRoot.resolve("m.csv")), "moved out of the root");
            assertTrue(Files.exists(serverRoot.resolve("archive/2026/06/14/m.csv")), "landed under the dated archive");
        }
    }

    @Test
    void postRenameAddsTheProcessedPrefix() throws Exception {
        Files.writeString(serverRoot.resolve("r.csv"), "z\n");
        try (CollectorConnector c = connector()) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            c.post(rf, new PostAction(PostAction.Kind.RENAME, null, java.util.Map.of()));
            assertFalse(Files.exists(serverRoot.resolve("r.csv")));
            assertTrue(Files.exists(serverRoot.resolve("processed_r.csv")), "renamed in place");
        }
    }

    @Test
    void endToEndParallelFetchWithMovePostAction(@TempDir Path dir) throws Exception {
        // Three CSVs, fetched 2-at-a-time (pool of 2 sessions), then MOVEd into archive/ on the server after each
        // is ingested. Proves parallel fetch + post-action wiring through CollectorProcessor.run.
        for (int i = 1; i <= 3; i++)
            Files.writeString(serverRoot.resolve("2020040" + i + "_feed.csv"),
                    "ID,AMT,EVENT_DATE\nr" + i + "," + i + ".0,2020-04-0" + i + "\n");

        ConnectionRegistry.register(profile());
        try {
            com.gamma.etl.PipelineConfig cfg = com.gamma.etl.PipelineConfig.load(
                    writeSftpPipeline(dir, "  fetch:\n    parallel_fetch: 2\n  post_action:\n    on_success: MOVE\n    archive_path: archive\n").toString());

            com.gamma.inspector.CollectorProcessor.run(cfg);

            try (var w = Files.walk(Path.of(cfg.dirs().database()))) {
                long outs = w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).count();
                assertTrue(outs >= 1, "the fetched SFTP files were ingested to output(s)");
            }
            // Every source file was MOVEd out of the root into the dated archive after processing.
            for (int i = 1; i <= 3; i++) {
                assertFalse(Files.exists(serverRoot.resolve("2020040" + i + "_feed.csv")),
                        "source file " + i + " moved out of the root by the MOVE post-action");
                assertTrue(Files.exists(serverRoot.resolve("archive/2020040" + i + "_feed.csv")),
                        "source file " + i + " landed under archive/");
            }
        } finally {
            ConnectionRegistry.remove("test-sftp");
        }
    }

    // ── connection workbench (probe · explore · sample) ─────────────────────────

    @Test
    void workbenchAnswersTheGradedChecksAndLeavesNoScratch() throws Exception {
        Files.writeString(serverRoot.resolve("a.csv"), "ID\n1\n");
        try (ConnectionWorkbench wb = new SftpConnectorFactory().workbench(profile())) {
            assertEquals("SSH auth ok", wb.check(ProbeCheck.AUTHENTICATE, 25).detail());
            assertTrue(wb.check(ProbeCheck.READ, 25).ok(), "base path listable");
            assertTrue(wb.check(ProbeCheck.WRITE, 25).ok(), "scratch write + delete against the real server");
            assertTrue(wb.check(ProbeCheck.LIST, 25).detail().contains("1 entries"));
        }
        try (var s = Files.list(serverRoot)) {
            assertEquals(1, s.count(), "the WRITE check left no scratch file behind");
        }
    }

    @Test
    void workbenchWrongPasswordFailsTheAuthenticateCheck() {
        ConnectionProfile bad = new ConnectionProfile("test-sftp", "sftp", "127.0.0.1", port, null, "/",
                "user", "WRONG", Map.of(), null);
        try (ConnectionWorkbench wb = new SftpConnectorFactory().workbench(bad)) {
            assertThrows(AcquisitionException.class, () -> wb.check(ProbeCheck.AUTHENTICATE, 25),
                    "a bad credential surfaces as a check failure, not a silent skip");
        } catch (AcquisitionException closeFailure) {
            // close() of a never-connected workbench is a no-op
        }
    }

    @Test
    void workbenchExploresLazilyAndJailsThePath() throws Exception {
        Files.createDirectories(serverRoot.resolve("inbox"));
        Files.writeString(serverRoot.resolve("inbox/feed.csv"), "ID,AMT\n1,2\n");
        Files.writeString(serverRoot.resolve("notes.txt"), "hello");
        try (ConnectionWorkbench wb = new SftpConnectorFactory().workbench(profile())) {
            List<ResourceNode> root = wb.explore("");
            assertEquals(List.of("inbox", "notes.txt"), root.stream().map(ResourceNode::name).toList());
            assertEquals(ResourceNode.Kind.DIR, root.get(0).kind());
            assertTrue(root.get(0).hasChildren());

            List<ResourceNode> inbox = wb.explore("inbox");
            assertEquals("inbox/feed.csv", inbox.get(0).path());
            assertEquals(ResourceNode.Kind.FILE, inbox.get(0).kind());
            assertNotNull(inbox.get(0).sizeBytes(), "SFTP listing carries the size");

            assertThrows(ConnectionWorkbench.PathEscape.class, () -> wb.explore("../etc"));
            assertThrows(ConnectionWorkbench.PathEscape.class, () -> wb.explore("/etc"));
        }
    }

    @Test
    void workbenchSampleFetchesAndParsesACsv() throws Exception {
        Files.createDirectories(serverRoot.resolve("inbox"));
        Files.writeString(serverRoot.resolve("inbox/feed.csv"), "ID,AMT\n1,2\n3,4\n5,6\n");
        try (ConnectionWorkbench wb = new SftpConnectorFactory().workbench(profile())) {
            SampleResult s = wb.sample("inbox/feed.csv", 2);
            assertEquals(List.of("ID", "AMT"), s.columns());
            assertEquals(2, s.rows().size());
            assertTrue(s.truncated(), "3 data rows, limit 2");

            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("inbox/ghost.csv", 5));
            assertThrows(ConnectionWorkbench.NoSuchPath.class, () -> wb.sample("inbox", 5),
                    "a directory is not sampleable");
        }
    }

    private Path writeSftpPipeline(Path dir) throws Exception {
        return writeSftpPipeline(dir, "");
    }

    private Path writeSftpPipeline(Path dir, String extraSourceBlock) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """);
        String toon = """
            name: SFTP_ETL
            version: 1
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
              log_dir: %s/logs
            collector:
              connector: sftp
              connection: test-sftp
            %s
            output:
              format: CSV
            processing:
              threads: 2
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              batch:
                max_files: 100
                max_bytes: 268435456
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          extraSourceBlock, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("sftp_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }
}
