package hauntedjvm.core.incident;

public enum AnomalyCategory {
    /** Records out of order with time itself. */
    TEMPORAL,
    /** Positions that contradict each other. */
    SPATIAL,
    /** Minds acting against their own nature. */
    BEHAVIORAL,
    /** Memories without a cause, or that contradict their cause. */
    MEMORY,
    /** Facility processes that do not behave like processes. */
    SYSTEM,
    /** Things the cameras and lights show that nothing caused. */
    VISUAL,
    /** Messages without a sender, or with an impossible one. */
    COMMUNICATION,
    /** Two things claiming to be the same thing. */
    IDENTITY,
    /** The simulation reacting to being watched. */
    OBSERVATION
}
