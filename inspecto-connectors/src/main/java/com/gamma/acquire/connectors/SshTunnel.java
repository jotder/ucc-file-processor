package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * An SSH bastion + loopback port-forward(s) (Data Acquisition — SSH tunnelling). Stands up an {@link SSHClient}
 * connection to a bastion host and forwards one or more {@code 127.0.0.1} ports through it to a target, so a
 * downstream client (SFTP, a JDBC driver, an FTP control + data channel, …) can connect to the local endpoint(s)
 * and have its traffic carried over the tunnel. Shared by {@link SftpConnector}, {@link DbExportConnector} and
 * {@link FtpConnector}.
 *
 * <p>{@link #open} forwards an ephemeral loopback port to the primary target (the {@link #localEndpoint()} the
 * client connects to). {@link #addForward} adds further forwards over the same bastion — used by FTP to tunnel
 * the server's <b>passive data-port range</b> (one forward per port, same port number on the loopback side so a
 * passive NAT-workaround lands on it). {@link #close()} tears down every forwarder, listening socket and accept
 * thread, then the bastion. The bastion's host key is verified per the supplied {@link HostKeyPolicy} (pin via
 * {@code known_hosts}/{@code strict_host_key}, or accept-on-connect when unset).
 */
public final class SshTunnel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SshTunnel.class);
    private static final int DEFAULT_SSH_PORT = 22;

    /** Authenticates an {@link SSHClient} for a given user + password reference (publickey or password). */
    @FunctionalInterface
    public interface Authenticator {
        void authenticate(SSHClient client, String username, String passwordRef) throws IOException;
    }

    /** One loopback→target forward carried over the bastion. */
    private record Forward(LocalPortForwarder forwarder, ServerSocket socket, Thread thread) {}

    private final SSHClient bastion;
    private final String label;
    private final InetSocketAddress local;          // the primary (control) endpoint
    private final List<Forward> forwards = new ArrayList<>();

    private SshTunnel(SSHClient bastion, String label, InetSocketAddress local) {
        this.bastion = bastion;
        this.label = label;
        this.local = local;
    }

    /** A fresh sshj client with the host-key verifier dictated by {@code policy} (shared by the connectors). */
    public static SSHClient newClient(HostKeyPolicy policy) throws IOException {
        SSHClient c = new SSHClient();
        policy.apply(c);
        return c;
    }

    /**
     * Open a tunnel: connect to {@code tunnel}'s bastion, authenticate with {@code auth}, and forward an
     * ephemeral loopback port to {@code targetHost:targetPort}. Returns a started tunnel whose
     * {@link #localEndpoint()} is what the downstream client should connect to. The bastion's host key is
     * verified per {@code bastionPolicy}.
     */
    public static SshTunnel open(ConnectionProfile.Tunnel tunnel, String targetHost, int targetPort,
                                 Authenticator auth, HostKeyPolicy bastionPolicy) throws IOException {
        SSHClient bastion = newClient(bastionPolicy);
        try {
            bastion.connect(tunnel.host(), tunnel.port() > 0 ? tunnel.port() : DEFAULT_SSH_PORT);
            auth.authenticate(bastion, tunnel.username(), tunnel.password());
            Forward control = bind(bastion, 0, targetHost, targetPort, tunnel.host());
            int localPort = control.socket().getLocalPort();
            SshTunnel t = new SshTunnel(bastion, tunnel.host(), new InetSocketAddress("127.0.0.1", localPort));
            t.forwards.add(control);
            log.info("SSH tunnel: 127.0.0.1:{} → {} → {}:{}", localPort, tunnel.endpoint(), targetHost, targetPort);
            return t;
        } catch (IOException e) {
            try { bastion.close(); } catch (IOException ignore) { }
            throw e;
        }
    }

    /**
     * Add another forward over the same bastion: bind {@code 127.0.0.1:localPort} (0 ⇒ ephemeral) and carry it
     * to {@code targetHost:targetPort}. Returns the bound local port. Used by FTP to tunnel each passive data
     * port (caller passes the same port number on both sides so a passive NAT-workaround connects to it).
     */
    public synchronized int addForward(int localPort, String targetHost, int targetPort) throws IOException {
        Forward f = bind(bastion, localPort, targetHost, targetPort, label);
        forwards.add(f);
        int actual = f.socket().getLocalPort();
        log.debug("SSH tunnel data forward: 127.0.0.1:{} → {}:{}", actual, targetHost, targetPort);
        return actual;
    }

    /** The local {@code 127.0.0.1:port} the downstream client should connect to (the primary/control forward). */
    public InetSocketAddress localEndpoint() {
        return local;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException first = null;
        for (Forward f : forwards) {
            // LocalPortForwarder is Closeable-shaped but does not implement AutoCloseable.
            try { f.forwarder().close(); } catch (IOException e) { if (first == null) first = e; }
            try { f.socket().close(); }    catch (IOException e) { if (first == null) first = e; }
            f.thread().interrupt();
        }
        forwards.clear();
        try { bastion.close(); } catch (IOException e) { if (first == null) first = e; }
        if (first != null) throw first;
    }

    /** Bind a loopback listener and start forwarding it through {@code bastion}; closes the socket on failure. */
    private static Forward bind(SSHClient bastion, int localPort, String targetHost, int targetPort, String label)
            throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", localPort));
            int actual = socket.getLocalPort();
            Parameters params = new Parameters("127.0.0.1", actual, targetHost, targetPort);
            LocalPortForwarder forwarder = bastion.newLocalPortForwarder(params, socket);
            Thread thread = new Thread(() -> {
                try { forwarder.listen(); }
                catch (IOException closed) { /* normal on close() */ }
            }, "ssh-tunnel-" + label + "-" + actual);
            thread.setDaemon(true);
            thread.start();
            return new Forward(forwarder, socket, thread);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignore) { }
            throw e;
        }
    }
}
