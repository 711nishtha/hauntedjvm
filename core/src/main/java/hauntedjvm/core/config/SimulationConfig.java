package hauntedjvm.core.config;

/**
 * Everything that determines a run, besides operator input.
 *
 * <p>Two engines built from equal configs produce identical event logs until the first
 * operator command. Real-time pacing is intentionally absent: how fast ticks are played back
 * is a property of the runner, not of the simulated world.
 *
 * @param seed             root of every random stream
 * @param persons          night-shift headcount; the only knob that scales entity count
 * @param anomalyIntensity multiplier on the anomaly engine's base rate; 1.0 is the tuned default
 * @param rareEventRate    multiplier on rare-event probabilities; 0 disables them
 * @param snapshotInterval ticks between replay checkpoints
 */
public record SimulationConfig(long seed, int persons, double anomalyIntensity, double rareEventRate,
                               int snapshotInterval) {

    public static final int DEFAULT_PERSONS = 16;
    public static final int MAX_PERSONS = 4000;

    public SimulationConfig {
        if (persons < 1 || persons > MAX_PERSONS) {
            throw new IllegalArgumentException("persons must be in 1.." + MAX_PERSONS + ": " + persons);
        }
        if (anomalyIntensity < 0 || anomalyIntensity > 10) {
            throw new IllegalArgumentException("anomalyIntensity must be in 0..10: " + anomalyIntensity);
        }
        if (rareEventRate < 0 || rareEventRate > 1000) {
            throw new IllegalArgumentException("rareEventRate must be in 0..1000: " + rareEventRate);
        }
        if (snapshotInterval < 1) {
            throw new IllegalArgumentException("snapshotInterval must be positive: " + snapshotInterval);
        }
    }

    public static SimulationConfig defaults(long seed) {
        return new SimulationConfig(seed, DEFAULT_PERSONS, 1.0, 1.0, 100);
    }

    public SimulationConfig withPersons(int n) {
        return new SimulationConfig(seed, n, anomalyIntensity, rareEventRate, snapshotInterval);
    }

    public SimulationConfig withAnomalyIntensity(double v) {
        return new SimulationConfig(seed, persons, v, rareEventRate, snapshotInterval);
    }

    public SimulationConfig withRareEventRate(double v) {
        return new SimulationConfig(seed, persons, anomalyIntensity, v, snapshotInterval);
    }

    public SimulationConfig withSnapshotInterval(int v) {
        return new SimulationConfig(seed, persons, anomalyIntensity, rareEventRate, v);
    }

    /** Seeds are shown as hex everywhere a human reads them. */
    public String seedHex() {
        return String.format("0x%016X", seed);
    }
}
