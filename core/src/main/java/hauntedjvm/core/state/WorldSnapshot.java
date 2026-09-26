package hauntedjvm.core.state;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.incident.IncidentState;
import java.util.List;

/**
 * Immutable capture of world state after the event with sequence {@code lastSeq} was applied.
 *
 * <p>Taking one is cheap: entities are immutable records, so consecutive snapshots share every
 * entity that did not change in between. The same type serves as a replay checkpoint, a render
 * frame handed across threads, and the on-disk snapshot format.
 *
 * @param entities ordered by serial
 */
public record WorldSnapshot(long tick, long lastSeq, int nextSerial, List<Entity> entities, IncidentState incident) {

    public WorldSnapshot {
        entities = List.copyOf(entities);
    }
}
