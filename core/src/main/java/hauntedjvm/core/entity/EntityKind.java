package hauntedjvm.core.entity;

public enum EntityKind {
    PERSON,
    PROCESS,
    DEVICE,
    ROOM,
    CAMERA,
    OBJECT,
    SYSTEM,
    UNKNOWN;

    /** Kinds that walk around and have a mind. */
    public boolean mobile() {
        return this == PERSON || this == UNKNOWN;
    }
}
