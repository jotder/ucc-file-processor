package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.CollectorConnector;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FTP through an SSH bastion — exercises {@link FtpConnector}'s tunnel path against an in-process Apache
 * FtpServer reached through an in-process Apache MINA SSHD bastion (local port forwarding enabled). Proves the
 * control connection is carried over the tunnel and a full passive flow (discover + fetch) completes. (The
 * passive data ports are not forwarded here — in-process they resolve over loopback; the passive-range forward
 * is production-only, with the range parser covered by {@link #parsesPassivePortRangesAndLists()}.)
 */
class FtpTunnelConnectorTest {

    private FtpServer ftp;
    private SshServer bastion;
    private Path serverRoot;
    private int ftpPort;
    private int sshPort;

    @BeforeEach
    void start(@TempDir Path tmp) throws Exception {
        serverRoot = Files.createDirectories(tmp.resolve("ftproot"));
        try (ServerSocket s = new ServerSocket(0)) { ftpPort = s.getLocalPort(); }

        FtpServerFactory ff = new FtpServerFactory();
        ListenerFactory lf = new ListenerFactory();
        lf.setServerAddress("127.0.0.1");
        lf.setPort(ftpPort);
        ff.addListener("default", lf.createListener());
        PropertiesUserManagerFactory umf = new PropertiesUserManagerFactory();
        umf.setPasswordEncryptor(new ClearTextPasswordEncryptor());
        UserManager um = umf.createUserManager();
        BaseUser user = new BaseUser();
        user.setName("user");
        user.setPassword("pw");
        user.setHomeDirectory(serverRoot.toString());
        user.setAuthorities(List.of(new WritePermission()));
        um.save(user);
        ff.setUserManager(um);
        ftp = ff.createServer();
        ftp.start();

        // bastion: an SSH server that permits local port forwarding (no shell/subsystem needed for direct-tcpip)
        bastion = SshServer.setUpDefaultServer();
        bastion.setHost("127.0.0.1");
        bastion.setPort(0);
        bastion.setKeyPairProvider(KeyPairProvider.wrap(KeyPairGenerator.getInstance("RSA").genKeyPair()));
        bastion.setPasswordAuthenticator((u, p, s) -> "jump".equals(u) && "secret".equals(p));
        bastion.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        bastion.start();
        sshPort = bastion.getPort();
    }

    @AfterEach
    void stop() throws Exception {
        if (ftp != null) ftp.stop();
        if (bastion != null) bastion.stop(true);
    }

    private FtpConnector connector() {
        ConnectionProfile p = new ConnectionProfile("test-ftp-tunnel", "ftp", "127.0.0.1", ftpPort, null, "",
                "user", "pw", Map.of(),
                new ConnectionProfile.Tunnel("127.0.0.1", sshPort, "jump", "secret"));
        return new FtpConnector(p, null);
    }

    @Test
    void discoversAndFetchesThroughTheBastion(@TempDir Path out) throws Exception {
        Files.writeString(serverRoot.resolve("via_bastion.csv"), "ID,AMT\n1,2\n");
        try (CollectorConnector c = connector()) {
            List<RemoteFile> found = c.discover(
                    new DiscoveryContext(List.of("*.csv"), List.of(), DiscoveryContext.UNBOUNDED));
            assertEquals(List.of("via_bastion.csv"), found.stream().map(RemoteFile::relativePath).toList(),
                    "LIST is carried over the tunnelled control connection");
            Path dest = out.resolve("via_bastion.csv");
            c.fetchTo(found.get(0), dest);
            assertEquals("ID,AMT\n1,2\n", Files.readString(dest), "RETR through the bastion returns the bytes");
        }
    }

    @Test
    void parsesPassivePortRangesAndLists() {
        assertArrayEquals(new int[0], FtpConnector.parsePorts(null));
        assertArrayEquals(new int[0], FtpConnector.parsePorts("  "));
        assertArrayEquals(new int[]{30000, 30001, 30002}, FtpConnector.parsePorts("30000-30002"));
        assertArrayEquals(new int[]{21, 22, 50000}, FtpConnector.parsePorts("21, 22, 50000"));
        assertArrayEquals(new int[]{40000, 40001, 9}, FtpConnector.parsePorts("40000-40001,9"));
    }
}
