package hauntedjvm.core.event;

import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.DetectedAnomaly;
import hauntedjvm.core.incident.ScheduledPerturbation;
import java.util.List;

/** Detection, escalation, and the anomaly engine's own (hidden) interventions. */
public sealed interface AnomalyEvent extends SimEvent {

    record AnomalyDetected(DetectedAnomaly anomaly) implements AnomalyEvent {
        @Override
        public List<EntityId> subjects() {
            return anomaly.subjects();
        }
    }

    record AnomalyResolved(String anomalyId) implements AnomalyEvent {
        @Override
        public List<EntityId> subjects() {
            return List.of();
        }
    }

    record AnomalyRetracted(String anomalyId, String reason) implements AnomalyEvent {
        @Override
        public List<EntityId> subjects() {
            return List.of();
        }
    }

    record AnomalyEscalated(int from, int to, double instability) implements AnomalyEvent {
        @Override
        public List<EntityId> subjects() {
            return List.of();
        }
    }

    /**
     * The anomaly engine acted. Visible only in debug mode: to the operator, the consequences
     * are all there is.
     *
     * @param scheduleId id of the chain step this fulfils, or -1
     */
    record PerturbationApplied(String name, AnomalyCategory category, List<EntityId> targets, String roomCode,
                               long scheduleId, String detail) implements AnomalyEvent {
        public PerturbationApplied {
            targets = List.copyOf(targets);
        }

        @Override
        public List<EntityId> subjects() {
            return targets;
        }

        @Override
        public boolean internal() {
            return true;
        }
    }

    record PerturbationScheduled(ScheduledPerturbation step) implements AnomalyEvent {
        @Override
        public List<EntityId> subjects() {
            return step.targets();
        }

        @Override
        public boolean internal() {
            return true;
        }
    }
}
