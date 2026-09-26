package hauntedjvm.core.entity;

import java.util.List;

/** Night-shift job. Determines which rooms make up a person's routine. */
public enum Role {
    GUARD(List.of("CORRIDOR-N", "CORRIDOR-S", "SHAFT-C", "ROOM-01", "ROOM-12", "ROOM-02")),
    TECHNICIAN(List.of("ROOM-03", "ROOM-06", "ROOM-03", "CORRIDOR-N", "ROOM-02")),
    ARCHIVIST(List.of("ROOM-04", "ROOM-05", "ROOM-04", "ROOM-02", "CORRIDOR-N")),
    RESEARCHER(List.of("ROOM-07", "ROOM-08", "ROOM-07", "ROOM-02", "ROOM-09")),
    MEDIC(List.of("ROOM-10", "ROOM-09", "ROOM-02", "CORRIDOR-S")),
    JANITOR(List.of("ROOM-11", "CORRIDOR-S", "ROOM-12", "CORRIDOR-N", "ROOM-02", "ROOM-10"));

    private final List<String> routine;

    Role(List<String> routine) {
        this.routine = routine;
    }

    /** Rooms this role visits; duplicates weight the choice. */
    public List<String> routine() {
        return routine;
    }
}
