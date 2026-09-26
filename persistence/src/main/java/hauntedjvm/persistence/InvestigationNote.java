package hauntedjvm.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Something the operator wrote down.
 *
 * @param targetRef what the note is attached to, e.g. {@code entity:#017}, {@code event:4410},
 *                  {@code anomaly:<id>}, or {@code session} for general notes
 * @param tick      the simulation tick being looked at when the note was written
 * @param writtenAt wall-clock time, for the operator's own record keeping
 */
public record InvestigationNote(UUID id, String targetRef, long tick, Instant writtenAt, String text) {

    public static final int MAX_LENGTH = 4_000;

    public InvestigationNote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetRef, "targetRef");
        Objects.requireNonNull(writtenAt, "writtenAt");
        text = Objects.requireNonNull(text, "text").strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("a note needs some text");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("notes are limited to " + MAX_LENGTH + " characters");
        }
    }

    public static InvestigationNote write(String targetRef, long tick, String text) {
        return new InvestigationNote(UUID.randomUUID(), targetRef, tick, Instant.now(), text);
    }
}
