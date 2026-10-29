package io.justinbeaver.edit;

/** A row to update or delete was not found (or matched more than one row); nothing was applied. */
public class EditConflictException extends RuntimeException {

    public EditConflictException(String message) {
        super(message);
    }
}
