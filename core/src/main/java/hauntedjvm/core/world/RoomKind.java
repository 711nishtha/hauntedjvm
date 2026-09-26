package hauntedjvm.core.world;

public enum RoomKind {
    ROOM,
    CORRIDOR,
    /** Present in the geometry but absent from every registry until something reveals it. */
    HIDDEN
}
