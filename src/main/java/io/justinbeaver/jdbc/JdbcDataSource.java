package io.justinbeaver.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.driver.DriverLoader;
import io.justinbeaver.driver.LoadedDriver;
import io.justinbeaver.network.SshTunnel;
import io.justinbeaver.network.SslProperties;
import io.justinbeaver.network.TunnelFactory;
import io.justinbeaver.provider.DataSourceProvider;

/**
 * A configured, not-yet-connected database (DBeaver's {@code JDBCDataSource}). Opens connections
 * by calling {@link Driver#connect} on the isolated driver instance, never through
 * {@link java.sql.DriverManager}, with the driver's class loader as thread context class loader
 * for the duration of the call (many drivers use {@code ServiceLoader}/resource lookups).
 * <p>
 * With SSH enabled, a tunnel is opened first and the driver connects to its local end; with SSL
 * enabled, the settings are mapped onto the driver's properties.
 */
public final class JdbcDataSource {

    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    /** A connection plus the tunnel it runs through (null without SSH); closing closes both. */
    public record Opened(Connection connection, SshTunnel tunnel) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            try {
                connection.close();
            } finally {
                if (tunnel != null) {
                    tunnel.close();
                }
            }
        }
    }

    private final DriverDescriptor descriptor;
    private final DataSourceProvider provider;
    private final DriverLoader driverLoader;
    private final ConnectionConfig config;
    private final TunnelFactory tunnels;

    public JdbcDataSource(DriverDescriptor descriptor, DataSourceProvider provider,
                          DriverLoader driverLoader, ConnectionConfig config, TunnelFactory tunnels) {
        this.descriptor = descriptor;
        this.provider = provider;
        this.driverLoader = driverLoader;
        this.config = config;
        this.tunnels = tunnels;
    }

    /** Without SSH support. */
    public JdbcDataSource(DriverDescriptor descriptor, DataSourceProvider provider,
                          DriverLoader driverLoader, ConnectionConfig config) {
        this(descriptor, provider, driverLoader, config, null);
    }

    /** The isolated loader of this data source's driver; loads the driver if needed. */
    public ClassLoader driverClassLoader() {
        return driverLoader.load(descriptor).classLoader();
    }

    public Opened open() throws SQLException {
        LoadedDriver loaded = driverLoader.load(descriptor);
        SshTunnel tunnel = null;
        ConnectionConfig effective = config;
        if (config.usesSsh()) {
            if (tunnels == null) {
                throw new SQLException("SSH tunnels are not available here", "08001");
            }
            if (config.url() != null && !config.url().isBlank()) {
                throw new SQLException("An SSH tunnel needs host and port; clear the custom JDBC URL", "08001");
            }
            Integer remotePort = config.port() != null ? config.port() : descriptor.defaultPort();
            if (remotePort == null) {
                throw new SQLException("An SSH tunnel needs the database port", "08001");
            }
            String remoteHost = config.host() == null || config.host().isBlank() ? "localhost" : config.host().trim();
            tunnel = tunnels.open(config.ssh(), remoteHost, remotePort);
            effective = config.withEndpoint("127.0.0.1", tunnel.localPort());
        }
        try {
            return new Opened(connect(loaded, effective), tunnel);
        } catch (SQLException | RuntimeException e) {
            if (tunnel != null) {
                tunnel.close();
            }
            throw e;
        }
    }

    private Connection connect(LoadedDriver loaded, ConnectionConfig effective) throws SQLException {
        String url = provider.buildUrl(descriptor, effective);
        Driver driver = loaded.driver();
        if (!driver.acceptsURL(url)) {
            throw new SQLException("Driver '%s' does not accept the connection URL".formatted(descriptor.id()));
        }
        Properties props = provider.connectionProperties(descriptor, effective);
        SslProperties.apply(descriptor, effective.ssl(), props);

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loaded.classLoader());
        try {
            Connection connection = driver.connect(url, props);
            if (connection == null) {
                throw new SQLException("Driver '%s' returned no connection (URL not for this driver)".formatted(descriptor.id()));
            }
            return connection;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Convenience for callers that never use SSH; fails when the config asks for a tunnel. */
    public Connection openConnection() throws SQLException {
        if (config.usesSsh()) {
            throw new SQLException("Use open() for connections through an SSH tunnel", "08001");
        }
        return open().connection();
    }

    /** Open, validate and close; reports what the server and driver say about themselves. */
    public ConnectionTestResult test() throws SQLException {
        try (Opened opened = open()) {
            Connection connection = opened.connection();
            if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                throw new SQLException("Connection opened but failed validation");
            }
            DatabaseMetaData meta = connection.getMetaData();
            return new ConnectionTestResult(
                    meta.getDatabaseProductName(), meta.getDatabaseProductVersion(),
                    meta.getDriverName(), meta.getDriverVersion());
        }
    }
}
