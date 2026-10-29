package io.justinbeaver.session;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One live JDBC connection to a saved connection. A JDBC {@link Connection} is not safe for
 * concurrent use, so every operation goes through {@link #call} which serializes access and sets the
 * driver's class loader as thread context class loader.
 * {@link #cancelRunning()} deliberately skips the lock so a long query can be cancelled.
 */
public final class DbSession implements AutoCloseable {

    private final String connectionId;
    private final Connection connection;
    private final ClassLoader driverClassLoader;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Statement running;
    private volatile boolean lost;
    private volatile boolean readOnly;

    private final AutoCloseable tunnel;

    /** @param tunnel SSH tunnel the connection runs through, closed with the session; may be null */
    public DbSession(String connectionId, Connection connection, ClassLoader driverClassLoader, AutoCloseable tunnel) {
        this.connectionId = connectionId;
        this.connection = connection;
        this.driverClassLoader = driverClassLoader;
        this.tunnel = tunnel;
    }

    public String connectionId() {
        return connectionId;
    }

    public <R> R call(SqlFunction<Connection, R> work) throws SQLException {
        lock.lock();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(driverClassLoader);
        try {
            return work.apply(connection);
        } catch (SQLException e) {
            if (connectionLost()) {
                lost = true; // the next SessionManager.require() reconnects
            }
            throw e;
        } finally {
            thread.setContextClassLoader(previous);
            lock.unlock();
        }
    }

    /** Read-only connection: statements that may change data are refused before they reach the database. */
    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean value) {
        readOnly = value;
    }

    /** True after a failure that left the connection closed or invalid (server restart, network drop...). */
    public boolean isLost() {
        return lost;
    }

    private boolean connectionLost() {
        try {
            return connection.isClosed() || !connection.isValid(2);
        } catch (SQLException | RuntimeException e) {
            return true;
        }
    }

    /** Register the statement being executed so it can be cancelled; call from inside {@link #call}. */
    public void setRunning(Statement statement) {
        running = statement;
    }

    /** @return true if a statement was running and a cancel was requested */
    public boolean cancelRunning() throws SQLException {
        Statement statement = running;
        if (statement == null) {
            return false;
        }
        statement.cancel();
        return true;
    }

    @Override
    public void close() throws SQLException {
        cancelQuietly();
        lock.lock();
        try {
            connection.close();
        } finally {
            lock.unlock();
            closeTunnel();
        }
    }

    private void closeTunnel() {
        if (tunnel != null) {
            try {
                tunnel.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private void cancelQuietly() {
        try {
            cancelRunning();
        } catch (SQLException ignored) {
            // closing anyway
        }
    }
}
