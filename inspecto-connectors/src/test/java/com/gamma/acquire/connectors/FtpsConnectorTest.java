package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.CollectorConnector;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FTPS hardening — exercises {@link FtpConnector} in explicit-TLS mode against an in-process Apache FtpServer
 * configured with TLS (a throwaway self-signed keystore from test resources). Proves the {@code AUTH TLS}
 * control channel + {@code PROT P} encrypted data channel work end-to-end (discover + fetch).
 */
class FtpsConnectorTest {

    private FtpServer server;
    private Path serverRoot;
    private int port;

    @BeforeEach
    void startServer(@TempDir Path tmp) throws Exception {
        serverRoot = Files.createDirectories(tmp.resolve("ftpsroot"));
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }   // grab a free port

        Path keystore = tmp.resolve("ftps.jks");
        try (InputStream in = getClass().getResourceAsStream("/ftps-test-keystore.jks")) {
            assertNotNull(in, "the throwaway FTPS test keystore is on the test classpath");
            Files.copy(in, keystore, StandardCopyOption.REPLACE_EXISTING);
        }

        FtpServerFactory factory = new FtpServerFactory();
        ListenerFactory lf = new ListenerFactory();
        lf.setServerAddress("127.0.0.1");
        lf.setPort(port);

        SslConfigurationFactory ssl = new SslConfigurationFactory();
        ssl.setKeystoreFile(keystore.toFile());
        ssl.setKeystorePassword("changeit");
        ssl.setSslProtocol("TLSv1.2");
        lf.setSslConfiguration(ssl.createSslConfiguration());
        lf.setImplicitSsl(false);   // explicit FTPS — the client upgrades with AUTH TLS after connect

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

    private FtpConnector connector() {
        // tls_trust=all accepts the self-signed test cert (still encrypted; not authenticated).
        ConnectionProfile p = new ConnectionProfile("test-ftps", "ftps", "127.0.0.1", port, null, "",
                "user", "pw", Map.of("tls", "explicit", "tls_trust", "all"), null);
        return new FtpConnector(p, null, TlsMode.EXPLICIT);
    }

    @Test
    void ftpsDiscoversAndFetchesOverTls(@TempDir Path out) throws Exception {
        Files.writeString(serverRoot.resolve("secure.csv"), "ID,AMT\n1,2\n");
        try (CollectorConnector c = connector()) {
            assertEquals("ftps", c.scheme(), "TLS mode reports the ftps scheme");
            List<RemoteFile> found = c.discover(
                    new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED));
            assertEquals(List.of("secure.csv"), found.stream().map(RemoteFile::relativePath).toList(),
                    "LIST over the encrypted data channel works");
            Path dest = out.resolve("secure.csv");
            c.fetchTo(found.get(0), dest);
            assertEquals("ID,AMT\n1,2\n", Files.readString(dest), "RETR over PROT P returns the bytes");
        }
    }

    @Test
    void tlsModeParses() {
        assertEquals(TlsMode.EXPLICIT, TlsMode.from("explicit", TlsMode.NONE));
        assertEquals(TlsMode.EXPLICIT, TlsMode.from("AUTH_TLS", TlsMode.NONE));
        assertEquals(TlsMode.IMPLICIT, TlsMode.from("implicit", TlsMode.NONE));
        assertEquals(TlsMode.NONE, TlsMode.from("none", TlsMode.EXPLICIT));
        assertEquals(TlsMode.EXPLICIT, TlsMode.from(null, TlsMode.EXPLICIT), "blank ⇒ the factory default");
        assertEquals(990, TlsMode.IMPLICIT.defaultPort());
        assertEquals(21, TlsMode.EXPLICIT.defaultPort());
    }
}
