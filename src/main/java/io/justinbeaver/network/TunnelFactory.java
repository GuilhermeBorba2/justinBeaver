package io.justinbeaver.network;

import java.nio.file.Path;
import java.sql.SQLException;

import io.justinbeaver.config.StorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Opens SSH tunnels with JustinBeaver's known_hosts plus the user's OpenSSH one. */
@Component
public class TunnelFactory {

    private final Path appKnownHosts;
    private final Path userKnownHosts;

    @Autowired
    public TunnelFactory(StorageProperties storage) {
        this(storage.dirOrDefault().resolve("known_hosts"), Path.of(System.getProperty("user.home"), ".ssh", "known_hosts"));
    }

    public TunnelFactory(Path appKnownHosts, Path userKnownHosts) {
        this.appKnownHosts = appKnownHosts;
        this.userKnownHosts = userKnownHosts;
    }

    public SshTunnel open(SshConfig ssh, String remoteHost, int remotePort) throws SQLException {
        return SshTunnel.open(ssh, remoteHost, remotePort, appKnownHosts, userKnownHosts);
    }
}
