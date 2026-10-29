package io.justinbeaver.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.justinbeaver.config.JustinBeaverProperties;
import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.provider.GenericDataSourceProvider;
import io.justinbeaver.provider.PostgresDataSourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderRegistryTest {

    private ProviderRegistry registry(Path extraDir) throws Exception {
        return new ProviderRegistry(List.of(new GenericDataSourceProvider(), new PostgresDataSourceProvider()),
                new JustinBeaverProperties(new JustinBeaverProperties.Drivers(extraDir),
                        new JustinBeaverProperties.Maven(null, List.of())));
    }

    @Test
    void loadsBundledDescriptorsAndRoutesToProviders() throws Exception {
        ProviderRegistry registry = registry(null);

        assertThat(registry.drivers()).extracting(DriverDescriptor::id)
                .contains("postgres-jdbc", "mysql-jdbc", "sqlite-jdbc");
        assertThat(registry.providerFor(registry.driver("postgres-jdbc")).id()).isEqualTo("postgresql");
        assertThat(registry.providerFor(registry.driver("mysql-jdbc")).id()).isEqualTo("generic");
    }

    @Test
    void loadsTheDriverCatalogWithGalleryMetadata() throws Exception {
        ProviderRegistry registry = registry(null);

        assertThat(registry.drivers()).hasSizeGreaterThan(90);
        DriverDescriptor oracle = registry.drivers().stream().filter(d -> d.label().equals("Oracle")).findFirst().orElseThrow();
        assertThat(oracle.className()).isEqualTo("oracle.jdbc.OracleDriver");
        assertThat(oracle.promoted()).isEqualTo(1);
        assertThat(oracle.libraries()).isNotEmpty();
        assertThat(registry.driver("postgres-jdbc").categories()).containsExactly("sql");
        // PostgreSQL-protocol databases get the PostgreSQL provider
        DriverDescriptor timescale = registry.drivers().stream().filter(d -> d.label().equals("TimescaleDB")).findFirst().orElseThrow();
        assertThat(registry.providerFor(timescale).id()).isEqualTo("postgresql");
    }

    @Test
    void unknownDriverIsReported() throws Exception {
        assertThatThrownBy(() -> registry(null).driver("nope")).isInstanceOf(UnknownDriverException.class);
    }

    @Test
    void userDescriptorsOverrideBundledOnes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("custom.yml"), """
                drivers:
                  - id: mysql-jdbc
                    label: My MySQL
                    className: com.mysql.cj.jdbc.Driver
                    libraries:
                      - file: /opt/drivers/mysql.jar
                """);

        DriverDescriptor mysql = registry(dir).driver("mysql-jdbc");

        assertThat(mysql.label()).isEqualTo("My MySQL");
        assertThat(mysql.libraries()).singleElement().extracting(l -> l.file()).isEqualTo("/opt/drivers/mysql.jar");
    }

    @Test
    void descriptorWithBothMavenAndFileIsRejected(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad.yml"), """
                drivers:
                  - id: bad
                    className: x.Y
                    libraries:
                      - maven: "a:b:1"
                        file: /x.jar
                """);

        assertThatThrownBy(() -> registry(dir)).hasStackTraceContaining("exactly one of");
    }

    @Test
    void savesCustomDriversThatSurviveARestartAndProtectsBundledIds(@TempDir Path dir) throws Exception {
        ProviderRegistry registry = registry(dir);
        DriverDescriptor custom = new DriverDescriptor("acme-db", null, "ACME DB", "com.acme.Driver", "jdbc:acme://{host}:{port}/{database}",
                9999, null, null, "generic", List.of(new io.justinbeaver.driver.DriverLibrary("com.acme:acme-jdbc:1.0", null, false)));

        registry.saveCustom(custom);

        assertThat(Files.exists(dir.resolve("custom-acme-db.yml"))).isTrue();
        ProviderRegistry restarted = registry(dir);
        assertThat(restarted.driver("acme-db").className()).isEqualTo("com.acme.Driver");
        assertThat(restarted.customIds()).containsExactly("acme-db");
        assertThatThrownBy(() -> restarted.saveCustom(new DriverDescriptor("postgres-jdbc", null, "x", "x.Driver", null, null, null, null,
                null, List.of(new io.justinbeaver.driver.DriverLibrary("a:b:1", null, false))))).isInstanceOf(IllegalArgumentException.class);

        restarted.deleteCustom("acme-db");
        assertThat(Files.exists(dir.resolve("custom-acme-db.yml"))).isFalse();
        assertThatThrownBy(() -> restarted.driver("acme-db")).isInstanceOf(UnknownDriverException.class);
    }
}
