package io.justinbeaver.jdbc;

import java.util.Map;

import io.justinbeaver.network.SshConfig;
import io.justinbeaver.network.SslConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * What the user typed into a connection form. Either fill host/port/database and let the driver's
 * URL template build the URL, or pass an explicit {@code url}.
 *
 * @param properties extra driver properties (e.g. {@code sslmode=require})
 */
public record ConnectionConfig(
        @NotBlank String driverId,
        String host,
        Integer port,
        String database,
        String user,
        String password,
        String url,
        Map<String, String> properties,
        @Valid SshConfig ssh,
        SslConfig ssl) {

    public ConnectionConfig {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /** Without SSH/SSL. */
    public ConnectionConfig(String driverId, String host, Integer port, String database, String user, String password,
                            String url, Map<String, String> properties) {
        this(driverId, host, port, database, user, password, url, properties, null, null);
    }

    public boolean usesSsh() {
        return ssh != null && ssh.enabled();
    }

    public ConnectionConfig withEndpoint(String newHost, int newPort) {
        return new ConnectionConfig(driverId, newHost, newPort, database, user, password, url, properties, ssh, ssl);
    }

    public ConnectionConfig withSecrets(String newPassword, SshConfig newSsh) {
        return new ConnectionConfig(driverId, host, port, database, user, newPassword, url, properties, newSsh, ssl);
    }

    @Override
    public String toString() {
        return "ConnectionConfig[driverId=%s, host=%s, port=%s, database=%s, user=%s, password=***, url=%s, ssh=%s, ssl=%s]"
                .formatted(driverId, host, port, database, user, url == null ? null : "<set>", ssh, ssl);
    }
}
