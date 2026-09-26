package hauntedjvm.core.world;

/** Static structure of a map cell. Dynamic state (open doors, light) lives in the world state. */
public enum Tile {
    OUTSIDE,
    WALL,
    FLOOR,
    TERMINAL,
    DOOR,
    SEALED_DOOR;

    /** Whether the cell can ever be walked on, ignoring door locks. */
    public boolean walkable() {
        return this == FLOOR || this == TERMINAL || this == DOOR;
    }
}
