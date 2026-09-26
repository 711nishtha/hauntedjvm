package hauntedjvm.core.entity;

public enum MemoryKind {
    /** Saw another entity. */
    SAW_ENTITY,
    /** Witnessed something that should not happen. */
    SAW_ANOMALY,
    /** Was told about something by someone else. */
    HEARD,
    /** Where an object was last seen. */
    OBJECT_LOCATION,
    /** A memory that survived a timeline rewind. */
    PREVIOUS_TIMELINE
}
