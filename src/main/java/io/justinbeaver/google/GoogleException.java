package io.justinbeaver.google;

/** A failure talking to Google or in the sync flow, with the HTTP status the API answers with. */
public class GoogleException extends RuntimeException {

    private final int status;

    public GoogleException(int status, String message) {
        super(message);
        this.status = status;
    }

    public GoogleException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
