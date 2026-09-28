package hauntedjvm.core.telemetry;

import hauntedjvm.core.anomaly.Escalation;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.state.WorldView;
import java.util.EnumMap;
import java.util.Map;

/**
 * Figures about the simulated world, for the SIMULATION and INCIDENT panels and the headless
 * summary. Everything here is derived from world state; none of it describes the real machine.
 *
 * @param divergence    how far this timeline has drifted from a coherent one, in {@code [0, 1)}:
 *                      a fictional figure computed from temporal anomalies, rewinds and memories
 *                      that survived them
 * @param orphanRefs    memories that point at entities which do not exist
 */
public record SimulationMetrics(
        long tick,
        int entities,
        Map<EntityKind, Integer> byKind,
        Map<EntityState, Integer> personStates,
        int corrupted,
        int missing,
        int aware,
        int unaccounted,
        int forgotten,
        double meanFear,
        double meanAwareness,
        double instability,
        int activeAnomalies,
        int totalAnomalies,
        int temporalAnomalies,
        double divergence,
        int orphanRefs,
        int badgesWithoutWearers) {

    public static SimulationMetrics of(WorldView w) {
        long t = w.tick();
        Map<EntityKind, Integer> byKind = new EnumMap<>(EntityKind.class);
        Map<EntityState, Integer> states = new EnumMap<>(EntityState.class);
        int entities = 0;
        int present = 0;
        double fear = 0;
        double awareness = 0;
        int forgotten = 0;
        int carried = 0;
        int orphanRefs = 0;
        int unaccounted = 0;
        int badges = 0;
        for (Entity e : w.entities()) {
            entities++;
            byKind.merge(e.kind(), 1, Integer::sum);
            if (e.kind() == EntityKind.UNKNOWN && e.present() && e.state() != EntityState.DORMANT) {
                unaccounted++;
            }
            if (e.kind() != EntityKind.PERSON) {
                continue;
            }
            states.merge(e.state(), 1, Integer::sum);
            if (e.forgotten()) {
                forgotten++;
            }
            if (e.state() == EntityState.MISSING && e.reportedPosition() != null) {
                badges++;
            }
            for (MemoryTrace m : e.mind().memories()) {
                if (m.kind() == MemoryKind.PREVIOUS_TIMELINE) {
                    carried++;
                }
                if (m.subject() != null && w.entity(m.subject()) == null) {
                    orphanRefs++;
                }
            }
            if (e.state() != EntityState.MISSING) {
                present++;
                fear += e.mind().fearAt(t);
                awareness += e.mind().awarenessAt(t);
            }
        }
        int active = 0;
        int temporal = 0;
        for (AnomalyRecord r : w.incident().anomalies()) {
            if (r.active()) {
                active++;
            }
            if (r.anomaly().category() == AnomalyCategory.TEMPORAL) {
                temporal++;
            }
        }
        double drift = temporal * 0.08 + w.incident().rewinds() * 0.35 + carried * 0.1;
        return new SimulationMetrics(t, entities, byKind, states,
                states.getOrDefault(EntityState.CORRUPTED, 0), states.getOrDefault(EntityState.MISSING, 0),
                states.getOrDefault(EntityState.AWARE, 0), unaccounted, forgotten,
                present == 0 ? 0 : fear / present, present == 0 ? 0 : awareness / present,
                Escalation.instability(w), active, w.incident().anomalies().size(), temporal,
                1 - Math.exp(-drift), orphanRefs, badges);
    }
}
