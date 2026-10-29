package io.justinbeaver.connection;

import java.util.Map;

import io.justinbeaver.network.SshConfig;
import io.justinbeaver.network.SslConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Create/update payload for a saved connection.
 *
 * @param password {@code null} on update keeps the stored password; anything else replaces it
 */
public record ConnectionInput(
        @NotBlank String name,
        @NotBlank String driverId,
        String host,
        Integer port,
        String database,
        String user,
        String password,
        String url,
        Map<String, String> properties,
        Boolean autoCommit,
        @Valid SshConfig ssh,
        SslConfig ssl,
        ConnectionType connectionType,
        Boolean readOnly,
        Boolean showAllDatabases,
        String folder) {

    /** Without SSH/SSL. */
    public ConnectionInput(String name, String driverId, String host, Integer port, String database, String user,
                           String password, String url, Map<String, String> properties, Boolean autoCommit) {
        this(name, driverId, host, port, database, user, password, url, properties, autoCommit, null, null);
    }

    /** Development, read-write. */
    public ConnectionInput(String name, String driverId, String host, Integer port, String database, String user,
                           String password, String url, Map<String, String> properties, Boolean autoCommit,
                           SshConfig ssh, SslConfig ssl) {
        this(name, driverId, host, port, database, user, password, url, properties, autoCommit, ssh, ssl, null, null, null, null);
    }

    public ConnectionInput {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    @Override
    public String toString() {
        return "ConnectionInput[name=%s, driverId=%s, password=***]".formatted(name, driverId);
    }
}
