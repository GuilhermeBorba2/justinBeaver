package io.justinbeaver.session;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.justinbeaver.connection.SavedConnection;
import io.justinbeaver.jdbc.ConnectionService;
import io.justinbeaver.jdbc.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Open connections keyed by saved-connection id (the "Connect / Disconnect" state in DBeaver). */
@Component
public class SessionManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConnectionService connections;
    private final Map<String, DbSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SavedConnection> settings = new ConcurrentHashMap<>();

    public SessionManager(ConnectionService connections) {
        this.connections = connections;
    }

    /** Idempotent: returns the existing session when already connected. */
    public DbSession connect(SavedConnection saved) throws SQLException {
        DbSession existing = sessions.get(saved.id());
        if (existing != null) {
            return existing;
        }
        JdbcDataSource dataSource = connections.dataSource(saved.toConfig());
        JdbcDataSource.Opened opened = dataSource.open();
        try {
            opened.connection().setAutoCommit(saved.autoCommit());
            if (saved.readOnly()) {
                try {
                    // the database enforces it where the driver supports the hint (PostgreSQL, MySQL, SQL Server...)
                    opened.connection().setReadOnly(true);
                } catch (SQLException e) {
                    log.info("Driver of {} cannot switch to read-only; JustinBeaver still refuses writes", saved.id());
                }
            }
        } catch (SQLException | RuntimeException e) {
            closeQuietly(opened);
            throw e;
        }
        DbSession session = new DbSession(saved.id(), opened.connection(), dataSource.driverClassLoader(), opened.tunnel());
        session.setReadOnly(saved.readOnly());
        DbSession raced = sessions.putIfAbsent(saved.id(), session);
        if (raced != null) {
            closeQuietly(opened);
            return raced;
        }
        settings.put(saved.id(), saved);
        return session;
    }

    /**
     * The open session; a session whose connection was lost is transparently replaced by a new one
     * (an open manual transaction on the lost connection is gone either way).
     */
    /** Separates a connection id from a database name in session keys: {@code <id>::<database>}. */
    public static final String DATABASE_SEPARATOR = "::";

    /**
     * The open session for a connection id, or for {@code <id>::<database>}: another database of the
     * same server, opened on first use with the connection's settings (PostgreSQL connects to one
     * database at a time).
     */
    public DbSession require(String connectionId) throws SQLException {
        DbSession session = sessions.get(connectionId);
        if (session == null) {
            int separator = connectionId.indexOf(DATABASE_SEPARATOR);
            if (separator > 0) {
                String baseId = connectionId.substring(0, separator);
                SavedConnection base = settings.get(baseId);
                if (base == null) {
                    throw new NotConnectedException(baseId);
                }
                String database = connectionId.substring(separator + DATABASE_SEPARATOR.length());
                return connect(base.onDatabase(connectionId, database));
            }
            throw new NotConnectedException(connectionId);
        }
        if (session.isLost()) {
            SavedConnection saved = settings.get(connectionId);
            if (saved == null) {
                throw new NotConnectedException(connectionId); // disconnected meanwhile
            }
            log.warn("Connection {} was lost; reconnecting", connectionId);
            if (sessions.remove(connectionId, session)) {
                try {
                    session.close();
                } catch (SQLException ignored) {
                    // already broken
                }
            }
            return connect(saved);
        }
        return session;
    }

    public boolean isConnected(String connectionId) {
        return sessions.containsKey(connectionId);
    }

    public void disconnect(String connectionId) throws SQLException {
        // the sessions opened on other databases of this server go too
        for (String key : List.copyOf(sessions.keySet())) {
            if (key.startsWith(connectionId + DATABASE_SEPARATOR)) {
                settings.remove(key);
                DbSession sub = sessions.remove(key);
                if (sub != null) {
                    try {
                        sub.close();
                    } catch (SQLException e) {
                        log.warn("Closing {} failed: {}", key, e.getMessage());
                    }
                }
            }
        }
        settings.remove(connectionId);
        DbSession session = sessions.remove(connectionId);
        if (session != null) {
            session.close();
        }
    }

    /** Settings of an open connection (or database session), null when not connected. */
    public SavedConnection settingsOf(String connectionId) {
        return settings.get(connectionId);
    }

    @Override
    public void destroy() {
        sessions.values().forEach(s -> {
            try {
                s.close();
            } catch (SQLException e) {
                log.warn("Closing session {} failed: {}", s.connectionId(), e.getMessage());
            }
        });
        sessions.clear();
    }

    private static void closeQuietly(JdbcDataSource.Opened opened) {
        try {
            opened.close();
        } catch (SQLException ignored) {
            // nothing to do
        }
    }
}
