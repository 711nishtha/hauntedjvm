package hauntedjvm.audio;

/** Short one-shot sounds triggered by things happening in the simulation or the interface. */
public enum Cue {
    /** An anomaly was logged. */
    BLIP(0.14),
    /** The incident level went up. */
    ESCALATE(1.4),
    /** A door somewhere closed. */
    DOOR(0.35),
    /** A transmission on the radio or intercom. */
    RADIO(0.55),
    /** A relay clicking as the operator switches feeds. */
    SWITCH(0.03),
    /** A burst of interference. */
    STATIC_BURST(0.35),
    /** Tape spooling backwards. */
    REWIND(0.9);

    private final double seconds;

    Cue(double seconds) {
        this.seconds = seconds;
    }

    public double seconds() {
        return seconds;
    }
}
