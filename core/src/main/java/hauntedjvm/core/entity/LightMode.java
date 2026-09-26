package hauntedjvm.core.entity;

public enum LightMode {
    ON(1.0),
    DIM(0.55),
    FLICKER(0.6),
    EMERGENCY(0.3),
    OFF(0.08);

    private final double level;

    LightMode(double level) {
        this.level = level;
    }

    /** Nominal illumination in {@code [0,1]}; drives perception and rendering. */
    public double level() {
        return level;
    }

    public boolean dark() {
        return level < 0.35;
    }
}
