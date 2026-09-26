package hauntedjvm.core.incident;

import hauntedjvm.core.entity.EntityId;
import java.util.List;

/**
 * A later step of a multi-part anomaly chain, kept in world state so that a restored snapshot
 * resumes the chain exactly where it left off.
 */
public record ScheduledPerturbation(
        long id,
        long dueTick,
        String perturbation,
        List<EntityId> targets,
        String roomCode,
        String detail) {

    public ScheduledPerturbation {
        targets = List.copyOf(targets);
    }
}
