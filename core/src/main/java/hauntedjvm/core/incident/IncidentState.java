package hauntedjvm.core.incident;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Facility-wide incident bookkeeping. Part of world state, so every piece of it is restored
 * with snapshots and reproduced by replay.
 *
 * @param level              current escalation level, 0..5
 * @param observedCamera     the feed the operator is looking at; the simulation can tell
 * @param anomalies          every anomaly ever detected in this timeline, in detection order
 * @param scheduled          pending steps of anomaly chains
 * @param lastPerturbation   tick of the most recent anomaly-engine intervention
 * @param rewinds            how many times this timeline has been rewound and resumed
 * @param lastInspected      reference string of the last thing the operator inspected
 */
public record IncidentState(
        int level,
        long levelSince,
        String observedCamera,
        Directive directive,
        List<AnomalyRecord> anomalies,
        List<ScheduledPerturbation> scheduled,
        long lastPerturbation,
        int perturbations,
        int rewinds,
        String lastInspected,
        long lastInspectedTick) {

    public IncidentState {
        anomalies = List.copyOf(anomalies);
        scheduled = List.copyOf(scheduled);
    }

    public static IncidentState initial(String firstCamera) {
        return new IncidentState(0, 0, firstCamera, null, List.of(), List.of(), -1, 0, 0, null, -1);
    }

    public EscalationLevel escalation() {
        return EscalationLevel.of(level);
    }

    public List<AnomalyRecord> active() {
        return anomalies.stream().filter(AnomalyRecord::active).toList();
    }

    public boolean hasActiveKey(String key) {
        for (AnomalyRecord r : anomalies) {
            if (r.active() && r.anomaly().key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    public boolean everDetected(String key) {
        for (AnomalyRecord r : anomalies) {
            if (r.anomaly().key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    public Optional<AnomalyRecord> anomaly(String id) {
        return anomalies.stream().filter(r -> r.anomaly().id().equals(id)).findFirst();
    }

    public IncidentState withLevel(int newLevel, long tick) {
        return new IncidentState(newLevel, tick, observedCamera, directive, anomalies, scheduled, lastPerturbation,
                perturbations, rewinds, lastInspected, lastInspectedTick);
    }

    public IncidentState withObservedCamera(String camera) {
        return new IncidentState(level, levelSince, camera, directive, anomalies, scheduled, lastPerturbation,
                perturbations, rewinds, lastInspected, lastInspectedTick);
    }

    public IncidentState withDirective(Directive d) {
        return new IncidentState(level, levelSince, observedCamera, d, anomalies, scheduled, lastPerturbation,
                perturbations, rewinds, lastInspected, lastInspectedTick);
    }

    public IncidentState withAnomaly(AnomalyRecord record) {
        List<AnomalyRecord> next = new ArrayList<>(anomalies);
        next.add(record);
        return new IncidentState(level, levelSince, observedCamera, directive, next, scheduled, lastPerturbation,
                perturbations, rewinds, lastInspected, lastInspectedTick);
    }

    public IncidentState withAnomalyStatus(String id, AnomalyRecord.Status status, long tick) {
        List<AnomalyRecord> next = new ArrayList<>(anomalies.size());
        for (AnomalyRecord r : anomalies) {
            next.add(r.anomaly().id().equals(id) ? r.withStatus(status, tick) : r);
        }
        return new IncidentState(level, levelSince, observedCamera, directive, next, scheduled, lastPerturbation,
                perturbations, rewinds, lastInspected, lastInspectedTick);
    }

    public IncidentState withScheduled(ScheduledPerturbation s) {
        List<ScheduledPerturbation> next = new ArrayList<>(scheduled);
        next.add(s);
        return new IncidentState(level, levelSince, observedCamera, directive, anomalies, next, lastPerturbation,
                perturbations, rewinds, lastInspected, lastInspectedTick);
    }

    /** Records an intervention and, if it fulfilled a scheduled step, removes that step. */
    public IncidentState withPerturbation(long tick, long scheduleId) {
        List<ScheduledPerturbation> next = scheduled;
        if (scheduleId >= 0) {
            next = new ArrayList<>(scheduled);
            next.removeIf(s -> s.id() == scheduleId);
        }
        return new IncidentState(level, levelSince, observedCamera, directive, anomalies, next, tick,
                perturbations + 1, rewinds, lastInspected, lastInspectedTick);
    }

    public IncidentState withRewind() {
        return new IncidentState(level, levelSince, observedCamera, directive, anomalies, scheduled, lastPerturbation,
                perturbations, rewinds + 1, lastInspected, lastInspectedTick);
    }

    public IncidentState withInspection(String ref, long tick) {
        return new IncidentState(level, levelSince, observedCamera, directive, anomalies, scheduled, lastPerturbation,
                perturbations, rewinds, ref, tick);
    }
}
