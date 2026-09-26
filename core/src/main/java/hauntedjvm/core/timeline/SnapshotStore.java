package hauntedjvm.core.timeline;

import hauntedjvm.core.state.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Replay checkpoints keyed by tick.
 *
 * <p>A {@link ConcurrentSkipListMap} is used because it is the one standard collection that
 * gives both concurrent reads during writes and an ordered {@code floorEntry}: the simulation
 * thread adds checkpoints while reconstruction tasks look up the nearest one below a target.
 */
public final class SnapshotStore {

    private final ConcurrentSkipListMap<Long, WorldSnapshot> snapshots = new ConcurrentSkipListMap<>();

    public void put(WorldSnapshot snapshot) {
        snapshots.put(snapshot.tick(), snapshot);
    }

    /** The latest checkpoint at or before {@code tick}, or {@code null}. */
    public WorldSnapshot floor(long tick) {
        Map.Entry<Long, WorldSnapshot> e = snapshots.floorEntry(tick);
        return e == null ? null : e.getValue();
    }

    public WorldSnapshot latest() {
        Map.Entry<Long, WorldSnapshot> e = snapshots.lastEntry();
        return e == null ? null : e.getValue();
    }

    public int size() {
        return snapshots.size();
    }

    public List<WorldSnapshot> all() {
        return new ArrayList<>(snapshots.values());
    }

    /** Checkpoints still valid for a log truncated after {@code tick} with {@code logSize} records. */
    public SnapshotStore prefix(long tick, long logSize) {
        SnapshotStore copy = new SnapshotStore();
        for (WorldSnapshot s : snapshots.headMap(tick, true).values()) {
            if (s.lastSeq() < logSize) {
                copy.put(s);
            }
        }
        return copy;
    }
}
