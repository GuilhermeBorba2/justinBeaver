package io.justinbeaver.provider;

import java.util.Properties;

import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.jdbc.ConnectionConfig;
import org.springframework.stereotype.Component;

/** Example of a database-specific provider: tags the session so it shows up in {@code pg_stat_activity}. */
@Component
public class PostgresDataSourceProvider implements DataSourceProvider {

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public Properties connectionProperties(DriverDescriptor driver, ConnectionConfig config) {
        Properties props = DataSourceProvider.super.connectionProperties(driver, config);
        props.putIfAbsent("ApplicationName", "JustinBeaver");
        return props;
    }
}
