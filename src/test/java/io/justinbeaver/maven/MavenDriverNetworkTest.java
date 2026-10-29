package io.justinbeaver.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.justinbeaver.config.JustinBeaverProperties;
import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.driver.DriverLibrary;
import io.justinbeaver.driver.DriverLoader;
import io.justinbeaver.jdbc.ConnectionConfig;
import io.justinbeaver.jdbc.JdbcDataSource;
import io.justinbeaver.provider.GenericDataSourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Hits Maven Central. Run with {@code mvn test -Djustinbeaver.it.network=true}. */
@EnabledIfSystemProperty(named = "justinbeaver.it.network", matches = "true")
class MavenDriverNetworkTest {

    @Test
    void downloadsSqliteDriverAndOpensInMemoryDatabase(@TempDir Path cache) throws Exception {
        MavenResolver resolver = new MavenResolver(new JustinBeaverProperties(
                new JustinBeaverProperties.Drivers(null),
                new JustinBeaverProperties.Maven(cache, List.of())));
        DriverLoader loader = new DriverLoader(resolver);
        DriverDescriptor sqlite = new DriverDescriptor("sqlite-jdbc", null, "SQLite", "org.sqlite.JDBC",
                "jdbc:sqlite:{file}", null, null, null, null,
                List.of(new DriverLibrary("org.xerial:sqlite-jdbc:3.47.1.0", null, false)));

        JdbcDataSource dataSource = new JdbcDataSource(sqlite, new GenericDataSourceProvider(), loader,
                new ConnectionConfig("sqlite-jdbc", null, null, ":memory:", null, null, null, Map.of()));

        assertThat(dataSource.test().productName()).isEqualTo("SQLite");
        assertThat(cache.resolve("org/xerial/sqlite-jdbc/3.47.1.0")).isDirectory();
    }
}
