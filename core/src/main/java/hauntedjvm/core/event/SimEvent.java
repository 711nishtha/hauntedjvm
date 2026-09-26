package hauntedjvm.core.event;

import hauntedjvm.core.entity.EntityId;
import java.util.List;

/**
 * Everything that can happen in the simulation.
 *
 * <p>Events are the only way world state changes: systems decide, events record the decision,
 * and {@code WorldState.apply} is the single place that turns a decision into state. Because the
 * hierarchy is sealed, the applier's switch is checked for exhaustiveness by the compiler, so a
 * new event type cannot be added without also teaching replay what it means.
 *
 * <p>Events are plain records with no behaviour and no framework annotations; serialisation
 * is configured from the outside by the persistence module.
 */
public sealed interface SimEvent
        permits EntityEvent, ProcessEvent, EnvironmentEvent, CommunicationEvent, AnomalyEvent, OperatorEvent {

    /** Entities this event is about, for indexing, filtering and inspector cross-links. */
    List<EntityId> subjects();

    /**
     * Bookkeeping of the anomaly engine itself. Hidden from the operator's event log unless the
     * application runs with {@code --debug}.
     */
    default boolean internal() {
        return false;
    }

    default String type() {
        return getClass().getSimpleName();
    }

    static List<EntityId> of(EntityId... ids) {
        List<EntityId> out = new java.util.ArrayList<>(ids.length);
        for (EntityId id : ids) {
            if (id != null && !out.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
