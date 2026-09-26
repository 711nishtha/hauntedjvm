package hauntedjvm.core.timeline;

import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.state.WorldSnapshot;
import hauntedjvm.core.state.WorldState;
import hauntedjvm.core.world.FacilityMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Checks recorded checkpoints against a fresh replay of the log.
 *
 * <p>Each checkpoint says "after event {@code lastSeq}, at tick {@code tick}, the world looked
 * exactly like this". The verifier replays from the empty world and compares at each of those
 * points. A mismatch means the log, the checkpoint, or the applier has changed meaning, and a
 * session that fails here would not replay faithfully.
 */
public final class ReplayVerifier {

    /** A checkpoint the replay disagreed with. */
    public record Mismatch(long tick, long lastSeq, String reason) {
    }

    private ReplayVerifier() {
    }

    public static List<Mismatch> verify(FacilityMap map, EventLog log, List<WorldSnapshot> checkpoints) {
        List<WorldSnapshot> ordered = new ArrayList<>(checkpoints);
        ordered.sort(Comparator.comparingLong(WorldSnapshot::lastSeq).thenComparingLong(WorldSnapshot::tick));
        List<Mismatch> mismatches = new ArrayList<>();
        WorldState state = WorldState.initial(map);
        long applied = -1;
        for (WorldSnapshot expected : ordered) {
            if (expected.lastSeq() >= log.size()) {
                mismatches.add(new Mismatch(expected.tick(), expected.lastSeq(), "checkpoint is beyond the end of the log"));
                continue;
            }
            while (applied < expected.lastSeq()) {
                applied++;
                state.apply(log.get(applied));
            }
            if (state.tick() > expected.tick()) {
                mismatches.add(new Mismatch(expected.tick(), expected.lastSeq(), "log has events later than the checkpoint"));
                continue;
            }
            WorldState probe = WorldState.restore(map, state.snapshot());
            probe.advanceTo(expected.tick());
            if (!probe.snapshot().equals(expected)) {
                mismatches.add(new Mismatch(expected.tick(), expected.lastSeq(), "replayed state differs"));
            }
        }
        return mismatches;
    }
}
