package hauntedjvm.core.timeline;

import hauntedjvm.core.state.WorldState;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.FacilityMap;

/**
 * A playhead over a recorded timeline.
 *
 * <p>Moving forward a little applies just the events in between; jumping backward or far ahead
 * restores the nearest checkpoint instead. Scrubbing and playback therefore cost roughly the
 * number of events actually crossed, independent of how long the recording is.
 *
 * <p>Safe to use from any single thread while the simulation keeps appending to the same
 * timeline, because the log and checkpoint store are both safe for concurrent reads.
 */
public final class ReplayCursor {

    /** Beyond this many ticks ahead, restoring a checkpoint is cheaper than walking forward. */
    private static final long WALK_LIMIT = 400;

    private final FacilityMap map;
    private final Timeline timeline;
    private WorldState state;
    private long appliedThrough = -1;

    public ReplayCursor(FacilityMap map, Timeline timeline) {
        this.map = map;
        this.timeline = timeline;
    }

    public Timeline timeline() {
        return timeline;
    }

    /** The world at the end of {@code tick}, clamped to the recorded range. */
    public WorldView seek(long tick) {
        long target = Math.clamp(tick, 0, timeline.headTick());
        if (state == null || target < state.tick() || target - state.tick() > WALK_LIMIT) {
            state = timeline.reconstruct(map, target);
            appliedThrough = state.lastSeq();
            return state;
        }
        long end = timeline.log().firstSeqAfterTick(target);
        for (long seq = appliedThrough + 1; seq < end; seq++) {
            state.apply(timeline.log().get(seq));
        }
        appliedThrough = Math.max(appliedThrough, end - 1);
        state.advanceTo(Math.max(state.tick(), target));
        return state;
    }

    public long tick() {
        return state == null ? -1 : state.tick();
    }
}
