package hauntedjvm.app.runtime;

/** Playback rates. At 1x the facility runs at {@link #BASE_TICKS_PER_SECOND} ticks per real second. */
public enum Speed {
    QUARTER(0.25, "0.25×"),
    HALF(0.5, "0.5×"),
    NORMAL(1, "1×"),
    DOUBLE(2, "2×"),
    QUADRUPLE(4, "4×"),
    FAST(8, "8×"),
    FASTER(16, "16×"),
    FASTEST(64, "64×");

    /** A 40-minute session at 1x covers about three and a half hours of facility time. */
    public static final double BASE_TICKS_PER_SECOND = 5.0;

    private final double factor;
    private final String label;

    Speed(double factor, String label) {
        this.factor = factor;
        this.label = label;
    }

    public double factor() {
        return factor;
    }

    public String label() {
        return label;
    }

    public Speed faster() {
        return values()[Math.min(ordinal() + 1, values().length - 1)];
    }

    public Speed slower() {
        return values()[Math.max(ordinal() - 1, 0)];
    }

    /** The preset closest to an arbitrary multiplier, e.g. from {@code --simulation-speed}. */
    public static Speed closest(double factor) {
        Speed best = NORMAL;
        for (Speed s : values()) {
            if (Math.abs(Math.log(s.factor / factor)) < Math.abs(Math.log(best.factor / factor))) {
                best = s;
            }
        }
        return best;
    }
}
