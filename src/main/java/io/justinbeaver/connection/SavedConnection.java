package io.justinbeaver.connection;

import java.util.Map;

import io.justinbeaver.jdbc.ConnectionConfig;
import io.justinbeaver.network.SshConfig;
import io.justinbeaver.network.SslConfig;

/** A saved connection with its decrypted password; never serialize this to a client. */
public record SavedConnection(
        String id,
        String name,
        String driverId,
        String host,
        Integer port,
        String database,
        String user,
        String password,
        String url,
        Map<String, String> properties,
        boolean autoCommit,
        SshConfig ssh,
        SslConfig ssl,
        ConnectionType connectionType,
        boolean readOnly,
        boolean showAllDatabases,
        String folder) {

    public SavedConnection {
        connectionType = connectionType == null ? ConnectionType.DEV : connectionType;
    }

    /** Development, read-write. */
    public SavedConnection(String id, String name, String driverId, String host, Integer port, String database, String user,
                           String password, String url, Map<String, String> properties, boolean autoCommit, SshConfig ssh,
                           SslConfig ssl) {
        this(id, name, driverId, host, port, database, user, password, url, properties, autoCommit, ssh, ssl, ConnectionType.DEV, false,
                false, null);
    }

    /** The same server and credentials on another database (PostgreSQL "show all databases"). */
    public SavedConnection onDatabase(String sessionKey, String otherDatabase) {
        return new SavedConnection(sessionKey, name, driverId, host, port, otherDatabase, user, password, url, properties, autoCommit,
                ssh, ssl, connectionType, readOnly, false, folder);
    }

    public ConnectionConfig toConfig() {
        return new ConnectionConfig(driverId, host, port, database, user, password, url, properties, ssh, ssl);
    }

    @Override
    public String toString() {
        return "SavedConnection[id=%s, name=%s, driverId=%s, password=***]".formatted(id, name, driverId);
    }
}
