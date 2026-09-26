package hauntedjvm.core.entity;

/**
 * Behavioural or operational state. One enum for every kind keeps the event schema flat;
 * {@link StateMachine} defines which states and transitions are legal for which kind.
 */
public enum EntityState {
    // persons
    NORMAL,
    SUSPICIOUS,
    AFRAID,
    INVESTIGATING,
    AVOIDING,
    FOLLOWING,
    ECHOING,
    CORRUPTED,
    AWARE,
    MISSING,
    // the unaccounted entity
    DORMANT,
    WANDERING,
    STALKING,
    MIMICKING,
    // processes
    RUNNING,
    BLOCKED,
    TERMINATED,
    // devices, cameras
    ONLINE,
    DEGRADED,
    OFFLINE,
    // rooms, objects, the facility itself
    PRESENT;

    /** States in which a person is visibly unwell, used by escalation and the UI palette. */
    public boolean disturbed() {
        return this == AFRAID || this == AVOIDING || this == ECHOING || this == CORRUPTED || this == AWARE;
    }
}
