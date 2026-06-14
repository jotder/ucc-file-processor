package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * An SSH bastion + loopback port-forward (Data Acquisition — SSH tunnelling). Stands up an {@link SSHClient}
 * connection to a bastion host and forwards an ephemeral {@code 127.0.0.1} port through it to a target
 * {@code host:port}, so a downstream client (SFTP, a JDBC driver, …) can connect to the local endpoint and have
 * its traffic carried over the tunnel. Shared by {@link SftpConnector} and {@link DbExportConnector}.
 *
 * <p>Open with {@link #open}, point the client at {@link #localEndpoint()}, and {@link #close()} when done
 * (closing the forwarder, the listening socket, the bastion, and interrupting the accept thread). The host-key
 * verifier is accept-on-connect (operator-configured profiles); strict pinning is a future hardening option.
 */
public final class SshTunnel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SshTunnel.class);
    private static final int DEFAULT_SSH_PORT = 22;

    /** Authenticates an {@link SSHClient} for a given user + password reference (publickey or password). */
    @FunctionalInterface
    public interface Authenticator {
        void authenticate(SSHClient client, String username, String passwordRef) throws IOException;
    }

    private final SSHClient bastion;
    private final LocalPortForwarder forwarder;
    private final ServerSocket socket;
    private final Thread thread;
    private final InetSocketAddress local;

    private SshTunnel(SSHClient bastion, LocalPortForwarder forwarder, ServerSocket socket, Thread thread,
                      InetSocketAddress local) {
        this.bastion = bastion;
        this.forwarder = forwarder;
        this.socket = socket;
        this.thread = thread;
        this.local = local;
    }

    /** A fresh sshj client with an accept-on-connect host-key verifier (shared by the connectors). */
    public static SSHClient newClient() {
        SSHClient c = new SSHClient();
        c.addHostKeyVerifier(new PromiscuousVerifier());
        return c;
    }

    /**
     * Open a tunnel: connect to {@code tunnel}'s bastion, authenticate with {@code auth}, and forward a loopback
     * port to {@code targetHost:targetPort}. Returns a started tunnel whose {@link #localEndpoint()} is what the
     * downstream client should connect to.
     */
    public static SshTunnel open(ConnectionProfile.Tunnel tunnel, String targetHost, int targetPort,
                                 Authenticator auth) throws IOException {
        SSHClient bastion = newClient();
        ServerSocket socket = null;
        try {
            bastion.connect(tunnel.host(), tunnel.port() > 0 ? tunnel.port() : DEFAULT_SSH_PORT);
            auth.authenticate(bastion, tunnel.username(), tunnel.password());

            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));   // ephemeral loopback port
            int localPort = socket.getLocalPort();

            Parameters params = new Parameters("127.0.0.1", localPort, targetHost, targetPort);
            LocalPortForwarder forwarder = bastion.newLocalPortForwarder(params, socket);
            final ServerSocket sock = socket;
            Thread thread = new Thread(() -> {
                try { forwarder.listen(); }
                catch (IOException closed) { /* normal on close() */ }
            }, "ssh-tunnel-" + tunnel.host());
            thread.setDaemon(true);
            thread.start();
            log.info("SSH tunnel: 127.0.0.1:{} → {} → {}:{}", localPort, tunnel.endpoint(), targetHost, targetPort);
            return new SshTunnel(bastion, forwarder, sock, thread, new InetSocketAddress("127.0.0.1", localPort));
        } catch (IOException e) {
            if (socket != null) try { socket.close(); } catch (IOException ignore) { }
            try { bastion.close(); } catch (IOException ignore) { }
            throw e;
        }
    }

    /** The local {@code 127.0.0.1:port} the downstream client should connect to. */
    public InetSocketAddress localEndpoint() {
        return local;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        // LocalPortForwarder is Closeable-shaped but does not implement AutoCloseable.
        try { forwarder.close(); } catch (IOException e) { first = e; }
        try { socket.close(); } catch (IOException e) { if (first == null) first = e; }
        thread.interrupt();
        try { bastion.close(); } catch (IOException e) { if (first == null) first = e; }
        if (first != null) throw first;
    }
}
