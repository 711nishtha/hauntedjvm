package hauntedjvm.core.entity;

/**
 * A value in {@code [0, 1]} that relaxes exponentially toward a baseline.
 *
 * <p>Fear and awareness change every tick, but emitting an event per entity per tick would
 * flood the log. Instead the value is stored as (level, time of last change) and evaluated
 * lazily: {@link #at(long)} is a pure function of the tick, so live simulation and replay agree
 * without any per-tick bookkeeping. Events are only needed when something bumps the value.
 */
public record Decaying(double baseline, double value, long since, double halfLife) {

    public Decaying {
        if (halfLife <= 0) {
            throw new IllegalArgumentException("halfLife must be positive");
        }
        baseline = Math.clamp(baseline, 0.0, 1.0);
        value = Math.clamp(value, 0.0, 1.0);
    }

    public static Decaying at(double baseline, long tick, double halfLife) {
        return new Decaying(baseline, baseline, tick, halfLife);
    }

    public double at(long tick) {
        long dt = Math.max(0, tick - since);
        return baseline + (value - baseline) * Math.pow(0.5, dt / halfLife);
    }

    public Decaying bump(long tick, double delta) {
        return new Decaying(baseline, at(tick) + delta, tick, halfLife);
    }

    public Decaying withBaseline(long tick, double newBaseline) {
        return new Decaying(newBaseline, at(tick), tick, halfLife);
    }
}
