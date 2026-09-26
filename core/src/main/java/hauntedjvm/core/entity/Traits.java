package hauntedjvm.core.entity;

import hauntedjvm.core.random.Rng;

/**
 * Fixed personality of a mind, each in {@code [0, 1]}.
 *
 * @param curiosity      pull toward anomalies instead of away from them
 * @param fear           how strongly frightening stimuli register
 * @param aggression     willingness to confront rather than flee
 * @param obedience      compliance with operator directives and routine
 * @param paranoia       distrust; distorts retold memories and drives avoidance
 * @param memoryStrength how slowly memories fade
 * @param awareness      sensitivity to the edges of the simulation itself
 */
public record Traits(
        double curiosity,
        double fear,
        double aggression,
        double obedience,
        double paranoia,
        double memoryStrength,
        double awareness) {

    public Traits {
        check("curiosity", curiosity);
        check("fear", fear);
        check("aggression", aggression);
        check("obedience", obedience);
        check("paranoia", paranoia);
        check("memoryStrength", memoryStrength);
        check("awareness", awareness);
    }

    public static Traits random(Rng rng) {
        return new Traits(
                rng.bell(0.5, 0.25),
                rng.bell(0.45, 0.25),
                rng.bell(0.3, 0.2),
                rng.bell(0.6, 0.2),
                rng.bell(0.35, 0.25),
                rng.bell(0.5, 0.25),
                rng.bell(0.25, 0.15));
    }

    /** Ticks for a memory's strength to halve. Between roughly 7 and 30 minutes of facility time. */
    public double memoryHalfLife() {
        return 420 + 1400 * memoryStrength;
    }

    private static void check(String name, double v) {
        if (!(v >= 0 && v <= 1)) {
            throw new IllegalArgumentException(name + " must be in [0,1]: " + v);
        }
    }
}
