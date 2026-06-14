package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.IntegrityChecker;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SourceConnector;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase E — exercises {@link FtpConnector} (commons-net client) against an in-process Apache FtpServer rooted
 * at a temp directory.
 */
class FtpConnectorTest {

    private FtpServer server;
    private Path serverRoot;
    private int port;

    @BeforeEach
    void startServer(@TempDir Path tmp) throws Exception {
        serverRoot = Files.createDirectories(tmp.resolve("ftproot"));
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }   // grab a free port

        FtpServerFactory factory = new FtpServerFactory();
        ListenerFactory lf = new ListenerFactory();
        lf.setServerAddress("127.0.0.1");
        lf.setPort(port);
        factory.addListener("default", lf.createListener());

        PropertiesUserManagerFactory umf = new PropertiesUserManagerFactory();
        umf.setPasswordEncryptor(new ClearTextPasswordEncryptor());
        UserManager um = umf.createUserManager();
        BaseUser user = new BaseUser();
        user.setName("user");
        user.setPassword("pw");
        user.setHomeDirectory(serverRoot.toString());
        user.setAuthorities(List.of(new WritePermission()));
        um.save(user);
        factory.setUserManager(um);

        server = factory.createServer();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    private SourceConnector connector() {
        ConnectionProfile p = new ConnectionProfile("test-ftp", "ftp", "127.0.0.1", port, null, "",
                "user", "pw", Map.of(), null);
        return new FtpConnector(p, null);
    }

    @Test
    void discoversMatchingFilesRecursively() throws Exception {
        Files.writeString(serverRoot.resolve("a.csv"), "ID,AMT\n1,2\n");
        Files.createDirectories(serverRoot.resolve("sub"));
        Files.writeString(serverRoot.resolve("sub/b.csv"), "ID,AMT\n3,4\n");
        Files.writeString(serverRoot.resolve("notes.txt"), "ignore");

        try (SourceConnector c = connector()) {
            List<RemoteFile> found = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED));
            List<String> rels = found.stream().map(RemoteFile::relativePath).sorted().toList();
            assertEquals(List.of("a.csv", "sub/b.csv"), rels);
            assertTrue(found.stream().allMatch(RemoteFile::hasSize), "FTP LIST carries sizes");
        }
    }

    @Test
    void fetchToWritesBytesAndIntegrityPasses(@TempDir Path local) throws Exception {
        String body = "ID,AMT\n7,42.0\n";
        Files.writeString(serverRoot.resolve("feed.csv"), body);

        try (SourceConnector c = connector()) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            Path dest = local.resolve("feed.csv");
            Path got = c.fetchTo(rf, dest);
            assertEquals(body, Files.readString(got, StandardCharsets.UTF_8));
            assertTrue(IntegrityChecker.verify(rf, got, "SHA-256").ok());
        }
    }

    @Test
    void openStreamsContent() throws Exception {
        String body = "p,q\n1,2\n";
        Files.writeString(serverRoot.resolve("s.csv"), body);
        try (SourceConnector c = connector()) {
            RemoteFile rf = c.discover(new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED)).get(0);
            try (InputStream in = c.open(rf)) {
                assertEquals(body, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
}
