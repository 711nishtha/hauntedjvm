package hauntedjvm.core.event;

import java.util.Objects;

/**
 * An event as stored in the log.
 *
 * @param seq          position in the log; dense, starting at 0
 * @param tick         the tick during which the event was appended; never decreases along the log
 * @param reportedTick the time the record claims. Equal to {@code tick} unless the anomaly engine
 *                     backdated or post-dated it; the detector looks for exactly that disagreement
 */
public record EventRecord(long seq, long tick, long reportedTick, SimEvent event) {

    public EventRecord {
        Objects.requireNonNull(event, "event");
        if (seq < 0) {
            throw new IllegalArgumentException("negative seq");
        }
    }

    public boolean misdated() {
        return reportedTick != tick;
    }
}
