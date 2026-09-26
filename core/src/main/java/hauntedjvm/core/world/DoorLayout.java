package hauntedjvm.core.world;

/**
 * A doorway between two rooms.
 *
 * @param index  stable index, used as a bit position in navigation masks
 * @param sealed true for doors that are walls until the simulation unseals them
 */
public record DoorLayout(int index, String code, Cell cell, String roomA, String roomB, boolean sealed) {

    public boolean connects(String room) {
        return roomA.equals(room) || roomB.equals(room);
    }

    public String otherSide(String room) {
        return roomA.equals(room) ? roomB : roomA;
    }
}
