package io.justinbeaver.registry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.justinbeaver.config.JustinBeaverProperties;
import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.provider.DataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Single lookup point for "which drivers exist and who handles them" (DBeaver's
 * {@code DataSourceProviderRegistry}). Descriptors come from {@code classpath:drivers/*.yml}, then
 * from the user's extra directory; a later file with the same driver id replaces the earlier one.
 * Providers are the {@link DataSourceProvider} beans.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final Map<String, DriverDescriptor> drivers = new TreeMap<>();
    private final Map<String, DataSourceProvider> providers;
    /** Ids of drivers defined in the UI's driver manager (files {@code custom-<id>.yml} in the extra directory). */
    private final java.util.Set<String> custom = new java.util.TreeSet<>();
    private final Path extraDir;

    public ProviderRegistry(List<DataSourceProvider> providers, JustinBeaverProperties properties) throws IOException {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(DataSourceProvider::id, Function.identity()));
        if (!this.providers.containsKey(DriverDescriptor.GENERIC_PROVIDER)) {
            throw new IllegalStateException("No 'generic' DataSourceProvider registered");
        }
        this.extraDir = properties.drivers().extraDir();
        loadBundled();
        loadExtra(extraDir);
        log.info("Registered {} driver(s), {} provider(s)", drivers.size(), this.providers.size());
    }

    public synchronized Collection<DriverDescriptor> drivers() {
        return List.copyOf(drivers.values());
    }

    public synchronized java.util.Set<String> customIds() {
        return java.util.Set.copyOf(custom);
    }

    private static final java.util.regex.Pattern CUSTOM_ID = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,60}");

    /**
     * Saves a user-defined driver as {@code custom-<id>.yml} in the extra directory and registers it; ids of
     * bundled drivers cannot be reused (that would replace them).
     */
    public synchronized DriverDescriptor saveCustom(DriverDescriptor driver) throws IOException {
        if (!CUSTOM_ID.matcher(driver.id()).matches()) {
            throw new IllegalArgumentException("Driver id: lower-case letters, digits, '.', '_' or '-'");
        }
        if (drivers.containsKey(driver.id()) && !custom.contains(driver.id())) {
            throw new IllegalArgumentException("A built-in driver already uses the id " + driver.id());
        }
        if (driver.libraries().isEmpty()) {
            throw new IllegalArgumentException("Add the driver JAR (Maven coordinates or a local .jar file)");
        }
        for (var library : driver.libraries()) {
            if (!library.isMaven() && !(library.file().endsWith(".jar") && Files.isRegularFile(Path.of(library.file())))) {
                throw new IllegalArgumentException("Not a JAR file: " + library.file());
            }
            if (library.isMaven() && !library.maven().matches("[\\w.-]+:[\\w.-]+:[\\w.+-]+")) {
                throw new IllegalArgumentException("Maven coordinates are group:artifact:version, not " + library.maven());
            }
        }
        if (extraDir == null) {
            throw new IllegalStateException("No justinbeaver.drivers.extra-dir configured");
        }
        Files.createDirectories(extraDir);
        yaml.writeValue(extraDir.resolve("custom-" + driver.id() + ".yml").toFile(), new DriverFile(List.of(driver)));
        drivers.put(driver.id(), driver);
        custom.add(driver.id());
        return driver;
    }

    public synchronized void deleteCustom(String id) throws IOException {
        if (!custom.contains(id)) {
            throw new UnknownDriverException(id);
        }
        Files.deleteIfExists(extraDir.resolve("custom-" + id + ".yml"));
        drivers.remove(id);
        custom.remove(id);
    }

    public synchronized DriverDescriptor driver(String id) {
        DriverDescriptor driver = drivers.get(id);
        if (driver == null) {
            throw new UnknownDriverException(id);
        }
        return driver;
    }

    /** Provider for the driver, falling back to {@code generic} when its own is not installed. */
    public DataSourceProvider providerFor(DriverDescriptor driver) {
        DataSourceProvider provider = providers.get(driver.providerId());
        if (provider == null) {
            log.warn("Driver {} wants provider '{}', which is not installed; using generic", driver.id(), driver.providerId());
            return providers.get(DriverDescriptor.GENERIC_PROVIDER);
        }
        return provider;
    }

    private void loadBundled() throws IOException {
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:drivers/*.yml")) {
            try (InputStream in = resource.getInputStream()) {
                register(yaml.readValue(in, DriverFile.class), resource.getDescription());
            }
        }
    }

    private void loadExtra(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")).sorted().toList()) {
                DriverFile parsed = yaml.readValue(file.toFile(), DriverFile.class);
                register(parsed, file.toString());
                if (file.getFileName().toString().startsWith("custom-")) {
                    parsed.drivers().forEach(d -> custom.add(d.id()));
                }
            }
        }
    }

    private void register(DriverFile file, String source) {
        for (DriverDescriptor driver : file.drivers()) {
            if (drivers.put(driver.id(), driver) != null) {
                log.info("Driver {} overridden by {}", driver.id(), source);
            }
        }
    }

    /** On-disk shape: one file may declare several drivers. */
    record DriverFile(List<DriverDescriptor> drivers) {
        DriverFile {
            drivers = drivers == null ? List.of() : List.copyOf(drivers);
        }
    }
}
