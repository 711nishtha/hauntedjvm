package hauntedjvm.core.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable static geometry of the facility: tiles, rooms, doors, camera mounts.
 *
 * <p>Everything that changes during a run (who is where, which doors are open, which lights
 * are on) lives in the world state. Keeping the map immutable means it can be shared freely
 * between the simulation thread, the renderer and any number of reconstructed timelines.
 */
public final class FacilityMap {

    private static final String DEFAULT_RESOURCE = "/hauntedjvm/world/facility-07.map";

    private final String name;
    private final int width;
    private final int height;
    private final Tile[] tiles;
    private final RoomLayout[] roomIndex;
    private final Map<String, RoomLayout> rooms;
    private final List<DoorLayout> doors;
    private final Map<Cell, DoorLayout> doorsByCell;
    private final Map<String, DoorLayout> doorsByCode;
    private final List<CameraMount> cameras;

    FacilityMap(String name, int width, int height, Tile[] tiles, RoomLayout[] roomIndex,
                List<RoomLayout> rooms, List<DoorLayout> doors, List<CameraMount> cameras) {
        if (doors.size() > Long.SIZE) {
            throw new IllegalArgumentException("navigation masks support at most 64 doors");
        }
        this.name = name;
        this.width = width;
        this.height = height;
        this.tiles = tiles;
        this.roomIndex = roomIndex;
        Map<String, RoomLayout> byCode = new LinkedHashMap<>();
        rooms.forEach(r -> byCode.put(r.code(), r));
        this.rooms = Collections.unmodifiableMap(byCode);
        this.doors = List.copyOf(doors);
        Map<Cell, DoorLayout> byCell = new LinkedHashMap<>();
        Map<String, DoorLayout> doorCodes = new LinkedHashMap<>();
        for (DoorLayout d : doors) {
            byCell.put(d.cell(), d);
            doorCodes.put(d.code(), d);
        }
        this.doorsByCell = Collections.unmodifiableMap(byCell);
        this.doorsByCode = Collections.unmodifiableMap(doorCodes);
        this.cameras = List.copyOf(cameras);
    }

    /** The bundled FACILITY-07 plan. Loaded once; the map is immutable. */
    public static FacilityMap facility07() {
        return Holder.FACILITY_07;
    }

    public static FacilityMap load(String name, InputStream in) {
        try {
            return MapParser.parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read map " + name, e);
        }
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean contains(Cell c) {
        return c.x() >= 0 && c.y() >= 0 && c.x() < width && c.y() < height;
    }

    public Tile tile(Cell c) {
        return contains(c) ? tiles[c.y() * width + c.x()] : Tile.OUTSIDE;
    }

    /** The room containing a cell, or {@code null} for walls and doorways. */
    public RoomLayout roomAt(Cell c) {
        return contains(c) ? roomIndex[c.y() * width + c.x()] : null;
    }

    public String roomCodeAt(Cell c) {
        RoomLayout r = roomAt(c);
        return r == null ? null : r.code();
    }

    public RoomLayout room(String code) {
        RoomLayout r = rooms.get(code);
        if (r == null) {
            throw new IllegalArgumentException("no such room: " + code);
        }
        return r;
    }

    public boolean hasRoom(String code) {
        return rooms.containsKey(code);
    }

    /** All rooms in declaration order, including hidden ones. */
    public List<RoomLayout> rooms() {
        return List.copyOf(rooms.values());
    }

    public List<DoorLayout> doors() {
        return doors;
    }

    public DoorLayout doorAt(Cell c) {
        return doorsByCell.get(c);
    }

    public DoorLayout door(String code) {
        return Objects.requireNonNull(doorsByCode.get(code), () -> "no such door: " + code);
    }

    public Optional<DoorLayout> findDoor(String code) {
        return Optional.ofNullable(doorsByCode.get(code));
    }

    public List<CameraMount> cameras() {
        return cameras;
    }

    /** Doors that are sealed by construction, as a navigation mask. */
    public long sealedMask() {
        long mask = 0;
        for (DoorLayout d : doors) {
            if (d.sealed()) {
                mask |= 1L << d.index();
            }
        }
        return mask;
    }

    /**
     * Whether an entity may step onto a cell given which doors are currently impassable.
     *
     * @param blockedDoors bit {@code i} set means door {@code i} is locked or sealed
     */
    public boolean passable(Cell c, long blockedDoors) {
        Tile t = tile(c);
        if (t == Tile.SEALED_DOOR || t == Tile.DOOR) {
            DoorLayout d = doorsByCell.get(c);
            return d != null && (blockedDoors & (1L << d.index())) == 0;
        }
        return t.walkable();
    }

    /** Rooms reachable in one step from a room, through doors or open passages, ignoring locks. */
    public List<String> adjacentRooms(String code) {
        List<String> out = new ArrayList<>();
        for (DoorLayout d : doors) {
            if (d.connects(code) && !d.sealed() && !out.contains(d.otherSide(code))) {
                out.add(d.otherSide(code));
            }
        }
        // The service shaft opens straight onto both corridors without a door.
        RoomLayout r = room(code);
        for (Cell c : r.cells()) {
            for (Cell n : List.of(c.offset(0, -1), c.offset(1, 0), c.offset(0, 1), c.offset(-1, 0))) {
                RoomLayout other = roomAt(n);
                if (other != null && other != r && !out.contains(other.code())) {
                    out.add(other.code());
                }
            }
        }
        return out;
    }

    private static final class Holder {
        static final FacilityMap FACILITY_07 = loadDefault();

        private static FacilityMap loadDefault() {
            try (InputStream in = FacilityMap.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("missing resource " + DEFAULT_RESOURCE);
                }
                return load("FACILITY-07", in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
