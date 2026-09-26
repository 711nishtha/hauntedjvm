package hauntedjvm.core.incident;

import hauntedjvm.core.entity.EntityId;
import java.util.List;

/**
 * An inconsistency the detector found in the world or its history.
 *
 * @param key      stable identity of the condition, e.g. {@code MEMORY:fabricated:#017:4410}; used to
 *                 avoid re-reporting the same thing every scan
 * @param severity 1 (curious) to 5 (the facility is no longer coherent)
 * @param evidence sequence numbers of the events that demonstrate the inconsistency
 */
public record DetectedAnomaly(
        String key,
        AnomalyCategory category,
        int severity,
        long tick,
        List<EntityId> subjects,
        String roomCode,
        String summary,
        List<Long> evidence) {

    public DetectedAnomaly {
        if (severity < 1 || severity > 5) {
            throw new IllegalArgumentException("severity must be 1..5: " + severity);
        }
        subjects = List.copyOf(subjects);
        evidence = List.copyOf(evidence);
    }

    /** Unique per occurrence: the same condition may be detected again after it resolves. */
    public String id() {
        return key + "@" + tick;
    }
}
