package io.justinbeaver.driver;

public class DriverLoadException extends RuntimeException {

    private final String driverId;

    public DriverLoadException(String driverId, String message, Throwable cause) {
        super("Driver '%s': %s".formatted(driverId, message), cause);
        this.driverId = driverId;
    }

    public String driverId() {
        return driverId;
    }
}
