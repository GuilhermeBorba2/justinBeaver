package io.justinbeaver.connection;

import java.util.Map;

import io.justinbeaver.network.SshConfig;
import io.justinbeaver.network.SslConfig;

/** What the API returns for a saved connection: everything except the password. */
public record ConnectionView(
        String id,
        String name,
        String driverId,
        String host,
        Integer port,
        String database,
        String user,
        String url,
        Map<String, String> properties,
        boolean autoCommit,
        boolean hasPassword,
        boolean connected,
        SshView ssh,
        SslConfig ssl,
        ConnectionType connectionType,
        boolean readOnly,
        boolean showAllDatabases,
        String folder) {

    /** SSH settings without the secrets. */
    public record SshView(boolean enabled, String host, Integer port, String user, SshConfig.Auth auth,
                          String privateKeyPath, Boolean acceptNewHostKeys, boolean hasPassword, boolean hasPassphrase) {
    }

    public static ConnectionView of(SavedConnection c, boolean connected) {
        SshConfig s = c.ssh();
        SshView ssh = s == null ? null : new SshView(s.enabled(), s.host(), s.port(), s.user(), s.auth(), s.privateKeyPath(),
                s.acceptNewHostKeys(), notEmpty(s.password()), notEmpty(s.passphrase()));
        return new ConnectionView(c.id(), c.name(), c.driverId(), c.host(), c.port(), c.database(), c.user(),
                c.url(), c.properties(), c.autoCommit(), notEmpty(c.password()), connected, ssh, c.ssl(), c.connectionType(),
                c.readOnly(), c.showAllDatabases(), c.folder());
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
