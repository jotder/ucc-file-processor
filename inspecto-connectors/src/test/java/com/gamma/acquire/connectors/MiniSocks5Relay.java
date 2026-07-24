package com.gamma.acquire.connectors;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A minimal SOCKS5 (no-auth) relay for connector proxy tests: accepts connections in a loop, honours the
 * handshake + CONNECT request on each, records the requested target, then pipes bytes to/from a real TCP
 * connection to that target until either side closes. Just enough of RFC 1928 to prove
 * {@link SocksProxySocketFactory} actually tunnels through a proxy rather than connecting directly.
 *
 * <p>Multi-connection on purpose: an FTP client routes its separate passive <em>data</em> channel through
 * the same socket factory, so the relay must serve more than one connection (SFTP multiplexes over a single
 * connection and only needs the first). The first CONNECT is always the control connection — assert against
 * {@link #firstConnectTarget}. Shared by {@code SftpConnectorTest} and {@code FtpConnectorTest}.
 */
final class MiniSocks5Relay implements AutoCloseable {
    private final ServerSocket listener;
    private final Thread acceptor;
    private final List<String> targets = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstKnown = new CountDownLatch(1);

    private MiniSocks5Relay(ServerSocket listener) {
        this.listener = listener;
        this.acceptor = new Thread(this::acceptLoop, "mini-socks5-relay");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    static MiniSocks5Relay start() throws IOException {
        ServerSocket ss = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        return new MiniSocks5Relay(ss);
    }

    int port() { return listener.getLocalPort(); }

    /** The first {@code host:port} a client asked this relay to CONNECT to (the control connection),
     *  waiting up to {@code timeoutMs}. */
    String firstConnectTarget(long timeoutMs) throws InterruptedException {
        firstKnown.await(timeoutMs, TimeUnit.MILLISECONDS);
        return targets.isEmpty() ? null : targets.get(0);
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                Thread t = new Thread(() -> serve(client), "mini-socks5-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;   // listener closed
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            var in = client.getInputStream();
            var out = client.getOutputStream();

            // Greeting: VER, NMETHODS, METHODS... — reply "no auth" unconditionally.
            int ver = in.read(), nMethods = in.read();
            in.readNBytes(new byte[nMethods], 0, nMethods);
            if (ver != 5) return;
            out.write(new byte[]{5, 0});
            out.flush();

            // Request: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT.
            in.read(); in.read(); in.read();   // ver, cmd, rsv
            int atyp = in.read();
            String host;
            if (atyp == 1) {   // IPv4
                byte[] addr = in.readNBytes(4);
                host = (addr[0] & 0xff) + "." + (addr[1] & 0xff) + "." + (addr[2] & 0xff) + "." + (addr[3] & 0xff);
            } else if (atyp == 3) {   // domain
                int len = in.read();
                host = new String(in.readNBytes(len), StandardCharsets.US_ASCII);
            } else {
                return;   // IPv6 unused by this test
            }
            int p = (in.read() << 8) | in.read();
            targets.add(host + ":" + p);
            firstKnown.countDown();

            try (Socket upstream = new Socket(host, p)) {
                out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});   // success, dummy BND.ADDR/PORT
                out.flush();
                Thread relayUp = new Thread(() -> relay(client, upstream));
                relayUp.setDaemon(true);
                relayUp.start();
                relay(upstream, client);
                relayUp.join(2000);
            }
        } catch (Exception ignore) {
            // best-effort test relay — a closed/failed leg just ends this connection
        }
    }

    private static void relay(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignore) {
            // normal once either side closes
        }
    }

    @Override
    public void close() throws IOException {
        listener.close();
        acceptor.interrupt();
    }
}
