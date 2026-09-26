package hauntedjvm.persistence;

import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.timeline.Timeline;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything an investigation consists of: the recorded timeline and what the operator made of it.
 *
 * <p>The timeline's log may still be growing on the simulation thread. {@code headTick} and
 * {@code eventCount} pin a consistent cut of it, captured between ticks, and are what gets saved.
 *
 * @param headTick   last tick included in this investigation
 * @param eventCount number of log records included; records beyond it are ignored
 * @param discovered ids of anomalies the operator has opened in the inspector
 */
public record Investigation(
        String sessionId,
        String title,
        Instant createdAt,
        SimulationConfig config,
        Timeline timeline,
        long headTick,
        long eventCount,
        List<InvestigationNote> notes,
        Set<String> discovered) {

    public Investigation {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(timeline, "timeline");
        if (eventCount < 0 || eventCount > timeline.log().size()) {
            throw new IllegalArgumentException("eventCount " + eventCount + " outside the log");
        }
        title = title == null || title.isBlank() ? "FACILITY-07 / " + config.seedHex() : title.strip();
        notes = List.copyOf(notes);
        discovered = new TreeSet<>(discovered);
    }
}
