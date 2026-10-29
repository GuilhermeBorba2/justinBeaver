package io.justinbeaver.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.jdbc.ConnectionConfig;

/**
 * Per-database behaviour that is not "load a JDBC driver": how to build the URL and which
 * properties to pass. DBeaver's {@code DBPDataSourceProvider}. Every Spring bean implementing this
 * is picked up by the {@code ProviderRegistry}; the {@code generic} one is the fallback.
 * <p>
 * This is the place to grow metadata extraction (catalogs, schemas, tables) per database later.
 */
public interface DataSourceProvider {

    /** Matches {@link DriverDescriptor#providerId()}. */
    String id();

    default String buildUrl(DriverDescriptor driver, ConnectionConfig config) {
        if (config.url() != null && !config.url().isBlank()) {
            return config.url();
        }
        if (driver.sampleUrl() == null || driver.sampleUrl().isBlank()) {
            throw new IllegalArgumentException("Driver '%s' has no URL template; pass 'url' explicitly".formatted(driver.id()));
        }
        Map<String, String> values = new HashMap<>();
        values.put("host", config.host());
        Integer port = config.port() != null ? config.port() : driver.defaultPort();
        values.put("port", port == null ? null : port.toString());
        String database = isBlank(config.database()) ? driver.defaultDatabase() : config.database();
        values.put("database", database);
        // placeholder names used by other drivers' templates for the same fields
        values.put("file", database);
        values.put("folder", database);
        values.put("db", database);
        values.put("server", config.host());
        values.put("username", isBlank(config.user()) ? driver.defaultUser() : config.user());
        values.put("password", config.password());
        return UrlTemplate.render(driver.sampleUrl(), values);
    }

    default Properties connectionProperties(DriverDescriptor driver, ConnectionConfig config) {
        Properties props = new Properties();
        config.properties().forEach(props::setProperty);
        String user = isBlank(config.user()) ? driver.defaultUser() : config.user();
        if (!isBlank(user)) {
            props.setProperty("user", user);
        }
        if (config.password() != null) {
            props.setProperty("password", config.password());
        }
        return props;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
