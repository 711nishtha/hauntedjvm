package hauntedjvm.core.timeline;

import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.state.WorldSnapshot;
import hauntedjvm.core.state.WorldState;
import hauntedjvm.core.world.FacilityMap;
import java.util.Objects;

/**
 * One recorded history: the event log plus the checkpoints taken along it.
 *
 * <p>A rewind does not edit a timeline; it produces a new one sharing the prefix. The old
 * object stays valid for anyone still holding it, which is what lets the UI keep drawing while
 * the simulation thread forks.
 */
public final class Timeline {

    private final EventLog log;
    private final SnapshotStore snapshots;
    private volatile long headTick;

    public Timeline(EventLog log, SnapshotStore snapshots, long headTick) {
        this.log = Objects.requireNonNull(log);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.headTick = headTick;
    }

    public static Timeline empty() {
        return new Timeline(new EventLog(), new SnapshotStore(), 0);
    }

    public EventLog log() {
        return log;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    /** The most recent simulated tick. Written by the simulation thread only. */
    public long headTick() {
        return headTick;
    }

    public void advanceHead(long tick) {
        if (tick < headTick) {
            throw new IllegalArgumentException("head cannot move backwards: " + tick + " < " + headTick);
        }
        headTick = tick;
    }

    /** A new timeline containing only what was recorded at or before {@code tick}. */
    public Timeline prefixThroughTick(long tick) {
        EventLog prefix = log.prefixThroughTick(tick);
        return new Timeline(prefix, snapshots.prefix(tick, prefix.size()), Math.min(tick, headTick));
    }

    /**
     * Rebuilds the world as it was at the end of {@code tick}: nearest checkpoint at or before
     * it, plus every event recorded after that checkpoint up to and including {@code tick}.
     * Sequence numbers, not ticks, decide where the checkpoint ends, because events can be
     * appended to a tick after its checkpoint was taken (operator commands while paused).
     */
    public WorldState reconstruct(FacilityMap map, long tick) {
        long target = Math.min(Math.max(0, tick), headTick);
        WorldSnapshot base = snapshots.floor(target);
        if (base == null) {
            throw new IllegalStateException("no checkpoint at or before tick " + target);
        }
        WorldState state = WorldState.restore(map, base);
        long end = log.firstSeqAfterTick(target);
        log.forEach(base.lastSeq() + 1, end, state::apply);
        state.advanceTo(Math.max(state.tick(), target));
        return state;
    }
}
