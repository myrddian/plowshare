package io.aeyer.plowshare.server.todos;

/** A batch that changed nothing, and the sentence that says which operation and why. */
public final class TodoRefused extends RuntimeException {

    public TodoRefused(String message) {
        super(message);
    }
}
