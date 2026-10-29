package io.justinbeaver.connection;

public class ConnectionNotFoundException extends RuntimeException {

    public ConnectionNotFoundException(String id) {
        super("Connection '%s' not found".formatted(id));
    }
}
