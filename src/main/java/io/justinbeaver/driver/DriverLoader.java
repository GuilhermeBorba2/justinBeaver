package io.justinbeaver.driver;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.justinbeaver.maven.MavenResolutionException;
import io.justinbeaver.maven.MavenResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link DriverDescriptor} into a live {@link Driver}: resolve JARs, build an isolated
 * {@link DriverClassLoader}, {@code Class.forName} the driver class, instantiate it. Loaded drivers
 * are cached by id (DBeaver's {@code DriverLoaderDescriptor}).
 * <p>
 * The driver is never registered with {@link java.sql.DriverManager} by us; callers use
 * {@link Driver#connect}. Drivers that self-register in a static initializer still end up in
 * {@code DriverManager}, and Java gives no way to deregister them from outside their own loader,
 * so {@link #evict(String)} cannot guarantee the class loader is garbage collected for those.
 */
@Component
public class DriverLoader {

    private static final Logger log = LoggerFactory.getLogger(DriverLoader.class);

    private final MavenResolver mavenResolver;
    private final Map<String, LoadedDriver> cache = new ConcurrentHashMap<>();

    public DriverLoader(MavenResolver mavenResolver) {
        this.mavenResolver = mavenResolver;
    }

    public LoadedDriver load(DriverDescriptor descriptor) {
        return cache.computeIfAbsent(descriptor.id(), id -> doLoad(descriptor));
    }

    /** Drop the cached driver and close its class loader; the next {@link #load} re-resolves. */
    public void evict(String driverId) {
        LoadedDriver removed = cache.remove(driverId);
        if (removed != null) {
            try {
                removed.classLoader().close();
            } catch (IOException e) {
                log.warn("Closing class loader of driver {} failed", driverId, e);
            }
        }
    }

    private LoadedDriver doLoad(DriverDescriptor descriptor) {
        Set<Path> jars = resolveJars(descriptor);
        if (jars.isEmpty()) {
            throw new DriverLoadException(descriptor.id(), "no JAR files resolved", null);
        }
        DriverClassLoader classLoader = new DriverClassLoader(descriptor.id(), toUrls(descriptor, jars));
        try {
            Class<?> type = Class.forName(descriptor.className(), true, classLoader);
            if (!Driver.class.isAssignableFrom(type)) {
                throw new DriverLoadException(descriptor.id(),
                        "%s does not implement java.sql.Driver".formatted(descriptor.className()), null);
            }
            Driver driver = (Driver) type.getDeclaredConstructor().newInstance();
            log.info("Loaded driver {} ({}) from {} file(s)", descriptor.id(), descriptor.className(), jars.size());
            return new LoadedDriver(descriptor, classLoader, driver);
        } catch (ClassNotFoundException e) {
            closeQuietly(classLoader);
            throw new DriverLoadException(descriptor.id(), "driver class %s not found in resolved JARs".formatted(descriptor.className()), e);
        } catch (ReflectiveOperationException | LinkageError e) {
            closeQuietly(classLoader);
            throw new DriverLoadException(descriptor.id(), "cannot instantiate " + descriptor.className(), e);
        } catch (RuntimeException e) {
            closeQuietly(classLoader);
            throw e;
        }
    }

    private Set<Path> resolveJars(DriverDescriptor descriptor) {
        Set<Path> jars = new LinkedHashSet<>();
        for (DriverLibrary library : descriptor.libraries()) {
            try {
                if (library.isMaven()) {
                    jars.addAll(mavenResolver.resolve(library.maven()));
                } else {
                    Path file = Path.of(library.file()).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(file)) {
                        throw new IllegalStateException("file not found: " + file);
                    }
                    jars.add(file);
                }
            } catch (MavenResolutionException | IllegalStateException e) {
                if (!library.optional()) {
                    throw new DriverLoadException(descriptor.id(), e.getMessage(), e);
                }
                log.warn("Skipping optional library of driver {}: {}", descriptor.id(), e.getMessage());
            }
        }
        return jars;
    }

    private static URL[] toUrls(DriverDescriptor descriptor, Set<Path> jars) {
        try {
            URL[] urls = new URL[jars.size()];
            int i = 0;
            for (Path jar : jars) {
                urls[i++] = jar.toUri().toURL();
            }
            return urls;
        } catch (MalformedURLException e) {
            throw new DriverLoadException(descriptor.id(), "bad JAR path", e);
        }
    }

    private static void closeQuietly(DriverClassLoader classLoader) {
        try {
            classLoader.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
