package io.justinbeaver.session;

public class NotConnectedException extends RuntimeException {

    public NotConnectedException(String connectionId) {
        super("Connection '%s' is not connected".formatted(connectionId));
    }
}
