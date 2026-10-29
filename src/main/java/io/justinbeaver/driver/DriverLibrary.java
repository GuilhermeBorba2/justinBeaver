package io.justinbeaver.driver;

/**
 * One JAR source for a driver, the equivalent of DBeaver's {@code <file type="jar" path="..."/>}.
 * Exactly one of {@code maven} (a {@code group:artifact:version} coordinate, resolved with its
 * runtime dependencies) or {@code file} (a local JAR path) is set.
 *
 * @param optional when true, a resolution failure is logged and skipped instead of failing the load
 */
public record DriverLibrary(String maven, String file, boolean optional) {

    public DriverLibrary {
        boolean hasMaven = maven != null && !maven.isBlank();
        boolean hasFile = file != null && !file.isBlank();
        if (hasMaven == hasFile) {
            throw new IllegalArgumentException("A driver library needs exactly one of 'maven' or 'file'");
        }
    }

    public boolean isMaven() {
        return maven != null && !maven.isBlank();
    }
}
