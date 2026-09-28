package hauntedjvm.app.runtime;

import hauntedjvm.core.state.WorldSnapshot;
import hauntedjvm.core.timeline.Timeline;

/**
 * What the simulation thread publishes after each tick or command: an immutable world snapshot
 * plus enough context to read the log consistently. The UI never touches the live engine.
 *
 * @param eventCount     log records that existed when the snapshot was taken
 * @param publishedNanos {@link System#nanoTime()} at publication, for render interpolation
 */
public record Frame(WorldSnapshot snapshot, Timeline timeline, long eventCount, long publishedNanos, Status status) {

    /**
     * Runner state.
     *
     * @param measuredTps ticks per second actually achieved over the last second
     * @param stepMicros  cost of the most recent tick
     * @param error       set if the simulation thread stopped on an exception
     */
    public record Status(long tick, Speed speed, boolean paused, double measuredTps, double stepMicros,
                         double targetTps, String error) {
    }
}
