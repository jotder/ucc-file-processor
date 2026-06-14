package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.IntegrityChecker;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SourceConnector;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
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

    @BeforeEach
    void startServer(@TempDir Path tmp) throws Exception {
        serverRoot = Files.createDirectories(tmp.resolve("sftproot"));
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
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

    private ConnectionProfile profile() {
        return new ConnectionProfile("test-sftp", "sftp", "127.0.0.1", port, null, "/",
                "user", "pw", Map.of(), null);
    }

    private SftpConnector connector() {
        return new SftpConnector(profile(), null);
    }

    @Test
    void discoversMatchingFilesRecursivelyWithListingMetadata() throws Exception {
        Files.writeString(serverRoot.resolve("a.csv"), "ID,AMT\n1,2\n");
        Files.createDirectories(serverRoot.resolve("sub"));
        Files.writeString(serverRoot.resolve("sub/b.csv"), "ID,AMT\n3,4\n");
        Files.writeString(serverRoot.resolve("notes.txt"), "ignore me");

        try (SourceConnector c = connector()) {
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

        try (SourceConnector c = connector()) {
            List<RemoteFile> found = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), 1));
            assertEquals(List.of("top.csv"), found.stream().map(RemoteFile::relativePath).toList());
        }
    }

    @Test
    void fetchToWritesBytesAndIntegrityPasses(@TempDir Path local) throws Exception {
        String body = "ID,AMT\n1,9.99\n2,8.88\n";
        Files.writeString(serverRoot.resolve("feed.csv"), body);

        try (SourceConnector c = connector()) {
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
        try (SourceConnector c = connector()) {
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

        try (SourceConnector c = connector()) {
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

            com.gamma.inspector.SourceProcessor.run(cfg);
            String afterFirst = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
            assertEquals(2, afterFirst.split("\n").length, "header + 1 batch row from the 2 fetched files");
            try (var w = Files.walk(Path.of(cfg.dirs().database()))) {
                assertTrue(w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv")),
                        "the fetched SFTP files were ingested to an output");
            }

            com.gamma.inspector.SourceProcessor.run(cfg);
            assertEquals(afterFirst, Files.readString(Path.of(cfg.dirs().batchesFilePath())),
                    "re-run finds the markers → fetches/processes nothing new");
        } finally {
            ConnectionRegistry.remove("test-sftp");
        }
    }

    private Path writeSftpPipeline(Path dir) throws Exception {
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
            source:
              connector: sftp
              connection: test-sftp
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
                          schema.toString().replace("\\", "/"));
        Path p = dir.resolve("sftp_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }
}
