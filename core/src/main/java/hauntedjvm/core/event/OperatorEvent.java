package hauntedjvm.core.event;

import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.incident.Directive;
import java.util.List;

/**
 * What the operator did. Operator input is recorded like everything else, which is what makes
 * a session with interventions replay exactly: the log contains the watching, not just the watched.
 */
public sealed interface OperatorEvent extends SimEvent {

    @Override
    default List<EntityId> subjects() {
        return List.of();
    }

    /** The operator switched the main feed. The simulation knows which room is being watched. */
    record CameraChanged(String from, String to) implements OperatorEvent {
    }

    record DirectiveIssued(Directive directive) implements OperatorEvent {
    }

    /** @param ref an inspector reference such as {@code entity:#017} or {@code anomaly:<id>} */
    record OperatorInspected(String ref) implements OperatorEvent {
    }

    record SimulationPaused() implements OperatorEvent {
    }

    record SimulationResumed() implements OperatorEvent {
    }

    /** A memory that made it through a rewind. */
    record CarriedMemory(EntityId owner, MemoryTrace trace) {
    }

    /**
     * The operator rewound to {@code toTick} and let the recording run again from there.
     * Everything after {@code toTick} was discarded, except what some minds refused to let go of.
     */
    record TimelineRewound(long fromTick, long toTick, List<CarriedMemory> residue) implements OperatorEvent {
        public TimelineRewound {
            residue = List.copyOf(residue);
        }

        @Override
        public List<EntityId> subjects() {
            return residue.stream().map(CarriedMemory::owner).distinct().toList();
        }
    }
}
