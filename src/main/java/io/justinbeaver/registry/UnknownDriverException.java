package io.justinbeaver.registry;

public class UnknownDriverException extends RuntimeException {

    public UnknownDriverException(String driverId) {
        super("Unknown driver '%s'".formatted(driverId));
    }
}
