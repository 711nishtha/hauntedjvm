package hauntedjvm.core.incident;

/**
 * Entity marks used by the anomaly engine. Marks are ordinary state (set through
 * {@code MarkChanged} events), which is how multi-tick anomalies survive snapshots and replay.
 */
public final class Marks {

    /** A terminated process that keeps allocating. */
    public static final String LINGERING = "lingering";
    /** Freezes while its room is on the operator's screen. */
    public static final String OBSERVER_SENSITIVE = "observer-sensitive";
    /** {@code echo:#012} - copies the movements of entity #012. */
    public static final String ECHO_PREFIX = "echo:";
    /** On the facility entity: {@code shy:<anomaly key>} - withdraws the record when inspected. */
    public static final String SHY_PREFIX = "shy:";
    /** On the facility entity: {@code rare:<name>} - a rare event that has already happened this run. */
    public static final String RARE_PREFIX = "rare:";

    private Marks() {
    }
}
