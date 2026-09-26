package hauntedjvm.persistence;

import java.io.IOException;

/** A session file that could be read but does not describe a valid, replayable investigation. */
public final class SessionFormatException extends IOException {

    public SessionFormatException(String message) {
        super(message);
    }

    public SessionFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
