package io.justinbeaver.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.SQLException;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local port forward {@code 127.0.0.1:<random>} → SSH server → {@code remoteHost:remotePort}. The
 * JDBC driver connects to the local end. Listens on loopback only, so other machines cannot use
 * the tunnel.
 */
public final class SshTunnel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SshTunnel.class);
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final SSHClient client;
    private final ServerSocket serverSocket;
    private final LocalPortForwarder forwarder;

    private SshTunnel(SSHClient client, ServerSocket serverSocket, LocalPortForwarder forwarder) {
        this.client = client;
        this.serverSocket = serverSocket;
        this.forwarder = forwarder;
    }

    public int localPort() {
        return serverSocket.getLocalPort();
    }

    /** Errors are reported as {@link SQLException} with SQLState 08001 (connection failure). */
    public static SshTunnel open(SshConfig ssh, String remoteHost, int remotePort, Path appKnownHosts, Path userKnownHosts)
            throws SQLException {
        if (ssh.host() == null || ssh.host().isBlank() || ssh.user() == null || ssh.user().isBlank()) {
            throw new SQLException("SSH tunnel needs the SSH host and user", "08001");
        }
        String target = "%s@%s:%d".formatted(ssh.user(), ssh.host(), ssh.portOrDefault());
        SSHClient client = new SSHClient();
        KnownHostsVerifier verifier;
        try {
            verifier = new KnownHostsVerifier(appKnownHosts, userKnownHosts, ssh.acceptNewHostKeysOrDefault());
        } catch (IOException e) {
            throw new SQLException("Cannot read known_hosts: " + e.getMessage(), "08001", e);
        }
        client.addHostKeyVerifier(verifier);
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ServerSocket serverSocket = null;
        try {
            client.connect(ssh.host().trim(), ssh.portOrDefault());
            authenticate(client, ssh);
            client.getConnection().getKeepAlive().setKeepAliveInterval(30);

            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Parameters params = new Parameters("127.0.0.1", serverSocket.getLocalPort(), remoteHost, remotePort);
            LocalPortForwarder forwarder = client.newLocalPortForwarder(params, serverSocket);
            ServerSocket socket = serverSocket;
            Thread listener = new Thread(() -> {
                try {
                    forwarder.listen();
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        log.warn("SSH tunnel via {} stopped: {}", target, e.getMessage());
                    }
                }
            }, "ssh-tunnel-" + ssh.host());
            listener.setDaemon(true);
            listener.start();
            log.info("SSH tunnel 127.0.0.1:{} -> {} -> {}:{}", serverSocket.getLocalPort(), target, remoteHost, remotePort);
            return new SshTunnel(client, serverSocket, forwarder);
        } catch (TransportException e) {
            closeQuietly(client, serverSocket);
            String reason = verifier.failure() != null ? verifier.failure() : e.getMessage();
            throw new SQLException("SSH connection to %s failed: %s".formatted(target, reason), "08001", e);
        } catch (UserAuthException e) {
            closeQuietly(client, serverSocket);
            throw new SQLException("SSH authentication to %s failed: %s".formatted(target, e.getMessage()), "28000", e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(client, serverSocket);
            String reason = verifier.failure() != null ? verifier.failure() : e.getMessage();
            throw new SQLException("SSH tunnel via %s failed: %s".formatted(target, reason), "08001", e);
        }
    }

    private static void authenticate(SSHClient client, SshConfig ssh) throws IOException {
        if (ssh.authOrDefault() == SshConfig.Auth.KEY) {
            if (ssh.privateKeyPath() == null || ssh.privateKeyPath().isBlank()) {
                throw new UserAuthException("no private key file configured");
            }
            KeyProvider key = ssh.passphrase() == null || ssh.passphrase().isEmpty()
                    ? client.loadKeys(ssh.privateKeyPath().trim())
                    : client.loadKeys(ssh.privateKeyPath().trim(), ssh.passphrase().toCharArray());
            client.authPublickey(ssh.user().trim(), key);
        } else {
            client.authPassword(ssh.user().trim(), ssh.password() == null ? "" : ssh.password());
        }
    }

    @Override
    public void close() {
        try {
            forwarder.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        closeQuietly(client, serverSocket);
    }

    private static void closeQuietly(SSHClient client, ServerSocket serverSocket) {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        try {
            client.disconnect();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
