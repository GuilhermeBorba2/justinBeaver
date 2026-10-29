package io.justinbeaver.driver;

import java.util.List;

/**
 * Declarative description of a JDBC driver (DBeaver's {@code <driver>} element). Pure data: no
 * class is loaded until {@link DriverLoader#load(DriverDescriptor)} is called.
 *
 * @param id              unique id, e.g. {@code postgres-jdbc}
 * @param providerId      id of the {@code DataSourceProvider} that handles this driver; {@code generic} by default
 * @param className       fully qualified {@code java.sql.Driver} implementation
 * @param sampleUrl       URL template, e.g. {@code jdbc:postgresql://{host}[:{port}]/[{database}]};
 *                        {@code [...]} groups are dropped when a placeholder inside is empty
 * @param defaultPort     used for {@code {port}} when the connection has none
 * @param defaultDatabase used for {@code {database}} when the connection has none
 * @param defaultUser     used when the connection has no user
 * @param libraries       JAR sources, in classpath order
 * @param categories      gallery categories (sql, analytic, embedded, hadoop, timeseries, file, fulltext, graph...)
 * @param promoted        popularity rank for the gallery's "Popular" view and score sort (lower is more popular)
 */
public record DriverDescriptor(
        String id,
        String providerId,
        String label,
        String className,
        String sampleUrl,
        Integer defaultPort,
        String defaultDatabase,
        String defaultUser,
        String dialect,
        List<DriverLibrary> libraries,
        List<String> categories,
        Integer promoted,
        String description) {

    /** Without gallery metadata. */
    public DriverDescriptor(String id, String providerId, String label, String className, String sampleUrl, Integer defaultPort,
                            String defaultDatabase, String defaultUser, String dialect, List<DriverLibrary> libraries) {
        this(id, providerId, label, className, sampleUrl, defaultPort, defaultDatabase, defaultUser, dialect, libraries, null, null, null);
    }

    public static final String GENERIC_PROVIDER = "generic";

    public DriverDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Driver descriptor without 'id'");
        }
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Driver '%s' has no 'className'".formatted(id));
        }
        providerId = providerId == null || providerId.isBlank() ? GENERIC_PROVIDER : providerId;
        label = label == null || label.isBlank() ? id : label;
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
