package io.justinbeaver.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.justinbeaver.config.JustinBeaverProperties;
import io.justinbeaver.jdbc.ConnectionConfig;
import io.justinbeaver.jdbc.ConnectionTestResult;
import io.justinbeaver.jdbc.JdbcDataSource;
import io.justinbeaver.maven.MavenResolver;
import io.justinbeaver.provider.GenericDataSourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline: H2 is on the test classpath only, so its JAR is handed to the loader as a local file. */
class DriverLoaderTest {

    private final DriverLoader loader = new DriverLoader(new MavenResolver(
            new JustinBeaverProperties(new JustinBeaverProperties.Drivers(null),
                    new JustinBeaverProperties.Maven(null, List.of()))));

    private static String h2Jar() throws Exception {
        return Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private DriverDescriptor h2(List<DriverLibrary> libraries) {
        return new DriverDescriptor("h2-test", null, "H2", "org.h2.Driver", null, null, null, null, null, libraries);
    }

    @Test
    void loadsDriverInIsolatedClassLoaderAndConnects() throws Exception {
        DriverDescriptor descriptor = h2(List.of(new DriverLibrary(null, h2Jar(), false)));

        LoadedDriver loaded = loader.load(descriptor);

        assertThat(loaded.classLoader()).isInstanceOf(DriverClassLoader.class);
        assertThat(loaded.driver().getClass().getClassLoader()).isSameAs(loaded.classLoader());
        assertThat(loaded.driver().getClass()).isNotEqualTo(org.h2.Driver.class);
        assertThat(loader.load(descriptor)).isSameAs(loaded);

        JdbcDataSource dataSource = new JdbcDataSource(descriptor, new GenericDataSourceProvider(), loader,
                new ConnectionConfig("h2-test", null, null, null, "sa", "", "jdbc:h2:mem:jb", Map.of()));
        ConnectionTestResult result = dataSource.test();

        assertThat(result.productName()).isEqualTo("H2");
    }

    @Test
    void rejectsUrlTheDriverDoesNotAccept() throws Exception {
        DriverDescriptor descriptor = h2(List.of(new DriverLibrary(null, h2Jar(), false)));
        JdbcDataSource dataSource = new JdbcDataSource(descriptor, new GenericDataSourceProvider(), loader,
                new ConnectionConfig("h2-test", null, null, null, null, null, "jdbc:postgresql://x/y", Map.of()));

        assertThatThrownBy(dataSource::openConnection).hasMessageContaining("does not accept");
    }

    @Test
    void missingRequiredLibraryFailsTheLoad(@TempDir Path dir) {
        DriverDescriptor descriptor = h2(List.of(new DriverLibrary(null, dir.resolve("nope.jar").toString(), false)));

        assertThatThrownBy(() -> loader.load(descriptor))
                .isInstanceOf(DriverLoadException.class)
                .hasMessageContaining("file not found");
    }

    @Test
    void driverClassMissingFromJarsFailsTheLoad() throws Exception {
        DriverDescriptor descriptor = new DriverDescriptor("bad-class", null, null, "com.nope.Driver", null, null,
                null, null, null, List.of(new DriverLibrary(null, h2Jar(), false)));

        assertThatThrownBy(() -> loader.load(descriptor))
                .isInstanceOf(DriverLoadException.class)
                .hasMessageContaining("com.nope.Driver");
    }
}
