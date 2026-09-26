package hauntedjvm.core.anomaly;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.state.WorldView;

/**
 * Instability and the escalation ladder.
 *
 * <p>Instability is a pure function of world state, recomputed whenever needed rather than
 * stored, so there is nothing to keep in sync and the UI can show the exact figure the director
 * is using. Levels move one step at a time with hysteresis: climbing needs a minute at the
 * current level, falling needs several minutes of calm.
 */
public final class Escalation {

    /** Instability needed to climb from level {@code i} to {@code i + 1}. */
    static final double[] THRESHOLDS = {1.2, 2.6, 4.5, 7.0, 9.5};
    /** Base dwell before climbing; each level adds {@link #RISE_TICKS_PER_LEVEL}. */
    static final long MIN_TICKS_BEFORE_RISE = 60;
    static final long RISE_TICKS_PER_LEVEL = 240;
    static final long MIN_TICKS_BEFORE_FALL = 400;
    static final double FALL_MARGIN = 0.75;
    static final double ANOMALY_CEILING = 5.0;

    private Escalation() {
    }

    /** Breakdown of the instability figure, for telemetry. */
    public record Reading(double anomalies, double fear, double awareness, double corruption, double missing,
                          double unknown, double rewinds) {
        public double total() {
            return anomalies + fear + awareness + corruption + missing + unknown + rewinds;
        }
    }

    public static double instability(WorldView w) {
        return read(w).total();
    }

    public static Reading read(WorldView w) {
        long t = w.tick();
        double raw = 0;
        for (AnomalyRecord r : w.incident().anomalies()) {
            if (r.active()) {
                // Superlinear in severity: one impossible room outweighs a dozen flickering lights.
                raw += Math.pow(r.anomaly().severity(), 1.5) * 0.12 * Math.exp(-(t - r.statusTick()) / 1800.0);
            }
        }
        // Saturating, so a flood of minor oddities cannot by itself push the facility to the top.
        double anomalies = ANOMALY_CEILING * (1 - Math.exp(-raw / ANOMALY_CEILING));
        double fear = 0;
        double awareness = 0;
        int present = 0;
        int corrupted = 0;
        int missing = 0;
        int aware = 0;
        for (Entity p : w.ofKind(EntityKind.PERSON)) {
            if (p.state() == EntityState.MISSING) {
                missing++;
                continue;
            }
            present++;
            fear += p.mind().fearAt(t);
            awareness += p.mind().awarenessAt(t);
            if (p.state() == EntityState.CORRUPTED) {
                corrupted++;
            }
            if (p.state() == EntityState.AWARE) {
                aware++;
            }
        }
        double meanFear = present == 0 ? 0 : fear / present;
        double meanAwareness = present == 0 ? 0 : awareness / present;
        double unknown = 0;
        for (Entity u : w.ofKind(EntityKind.UNKNOWN)) {
            if (u.present() && u.state() != EntityState.DORMANT) {
                unknown += u.state() == EntityState.MIMICKING ? 1.2 : 0.8;
            }
        }
        double headcount = Math.max(1, present + missing);
        // Counts are normalised by headcount so a large roster does not escalate faster by itself.
        double scale = 16.0 / Math.max(16.0, headcount);
        return new Reading(anomalies, meanFear * 2.0, meanAwareness * 2.0,
                (corrupted * 0.7 + aware * 0.4) * scale, missing * 0.8 * scale, unknown,
                0.4 * w.incident().rewinds());
    }

    /** The level the facility should move to from {@code current}, one step at most. */
    public static int nextLevel(int current, double instability, long ticksAtLevel) {
        long dwell = MIN_TICKS_BEFORE_RISE + RISE_TICKS_PER_LEVEL * current;
        if (current < THRESHOLDS.length && instability >= THRESHOLDS[current] && ticksAtLevel >= dwell) {
            return current + 1;
        }
        if (current > 0 && instability < THRESHOLDS[current - 1] * FALL_MARGIN && ticksAtLevel >= MIN_TICKS_BEFORE_FALL) {
            return current - 1;
        }
        return current;
    }
}
