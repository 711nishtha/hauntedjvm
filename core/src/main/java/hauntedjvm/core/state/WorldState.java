package hauntedjvm.core.state;

import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.entity.SystemFacet;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.incident.IncidentState;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.FacilityMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The mutable present. Owned by exactly one thread at a time: the simulation thread for the
 * live world, or a reconstruction task for a rebuilt past.
 *
 * <p>State changes only through {@link #apply(EventRecord)}. Everything else here is either a
 * query or an index kept consistent by the applier. All iteration orders are defined (tree maps
 * and sorted sets), because hash-order iteration is the classic way deterministic simulations
 * quietly stop being deterministic.
 */
public final class WorldState implements WorldView {

    private final FacilityMap map;
    private final TreeMap<EntityId, Entity> entities = new TreeMap<>();
    private final EnumMap<EntityKind, TreeSet<EntityId>> byKind = new EnumMap<>(EntityKind.class);
    private final Map<String, TreeSet<EntityId>> occupancy = new HashMap<>();
    private final Map<String, EntityId> rooms = new HashMap<>();
    private final Map<String, EntityId> cameras = new HashMap<>();
    private final Map<Cell, EntityId> doors = new HashMap<>();
    private final Map<String, EntityId> processes = new HashMap<>();
    private final EventApplier applier = new EventApplier(this);
    private EntityId systemId;
    private IncidentState incident;
    private long tick;
    private long lastSeq = -1;
    private int nextSerial;
    private long blockedDoors;

    public WorldState(FacilityMap map, IncidentState incident) {
        this.map = Objects.requireNonNull(map);
        this.incident = Objects.requireNonNull(incident);
        this.blockedDoors = map.sealedMask();
        for (EntityKind k : EntityKind.values()) {
            byKind.put(k, new TreeSet<>());
        }
    }

    /** Rebuilds a state from a snapshot. The result shares the snapshot's immutable entities. */
    public static WorldState restore(FacilityMap map, WorldSnapshot snapshot) {
        WorldState s = new WorldState(map, snapshot.incident());
        for (Entity e : snapshot.entities()) {
            s.put(e);
        }
        s.tick = snapshot.tick();
        s.lastSeq = snapshot.lastSeq();
        s.nextSerial = snapshot.nextSerial();
        return s;
    }

    public WorldSnapshot snapshot() {
        return new WorldSnapshot(tick, lastSeq, nextSerial, new ArrayList<>(entities.values()), incident);
    }

    /** Applies one logged event. The only mutator of world state. */
    public void apply(EventRecord record) {
        if (record.seq() <= lastSeq) {
            throw new IllegalStateException("event " + record.seq() + " already applied (at " + lastSeq + ")");
        }
        applier.apply(record);
        lastSeq = record.seq();
        tick = Math.max(tick, record.tick());
    }

    /** Moves the clock forward. Ticks without events still pass. */
    public void advanceTo(long newTick) {
        if (newTick < tick) {
            throw new IllegalArgumentException("time does not run backwards here: " + newTick + " < " + tick);
        }
        tick = newTick;
    }

    /** Reserves the serial for an entity about to be created. */
    public EntityId allocateId() {
        return EntityId.of(nextSerial);
    }

    // ---- queries -------------------------------------------------------------------------

    @Override
    public FacilityMap map() {
        return map;
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public long lastSeq() {
        return lastSeq;
    }

    public int nextSerial() {
        return nextSerial;
    }

    @Override
    public Entity entity(EntityId id) {
        return id == null ? null : entities.get(id);
    }

    @Override
    public List<Entity> entities() {
        return new ArrayList<>(entities.values());
    }

    @Override
    public List<Entity> ofKind(EntityKind kind) {
        TreeSet<EntityId> ids = byKind.get(kind);
        List<Entity> out = new ArrayList<>(ids.size());
        for (EntityId id : ids) {
            out.add(entities.get(id));
        }
        return out;
    }

    @Override
    public List<Entity> occupants(String roomCode) {
        TreeSet<EntityId> ids = occupancy.get(roomCode);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Entity> out = new ArrayList<>(ids.size());
        for (EntityId id : ids) {
            out.add(entities.get(id));
        }
        return out;
    }

    @Override
    public Entity room(String roomCode) {
        return entity(rooms.get(roomCode));
    }

    @Override
    public Entity camera(String cameraCode) {
        return entity(cameras.get(cameraCode));
    }

    @Override
    public Entity doorAt(Cell cell) {
        return entity(doors.get(cell));
    }

    @Override
    public Entity process(String command) {
        return entity(processes.get(command));
    }

    @Override
    public Entity system() {
        return entity(systemId);
    }

    @Override
    public LightMode light(String roomCode) {
        Entity room = room(roomCode);
        return room != null && room.facet() instanceof RoomFacet rf ? rf.light() : LightMode.OFF;
    }

    @Override
    public long blockedDoors() {
        return blockedDoors;
    }

    @Override
    public IncidentState incident() {
        return incident;
    }

    // ---- mutation, reserved for EventApplier -----------------------------------------------

    void put(Entity e) {
        Entity previous = entities.put(e.id(), e);
        if (previous != null) {
            unindexPosition(previous);
        } else {
            byKind.get(e.kind()).add(e.id());
            nextSerial = Math.max(nextSerial, e.id().value() + 1);
            switch (e.facet()) {
                case RoomFacet r -> rooms.put(r.code(), e.id());
                case CameraFacet c -> cameras.put(c.code(), e.id());
                case DoorFacet d -> doors.put(e.position(), e.id());
                case ProcessFacet p -> processes.put(p.command(), e.id());
                case SystemFacet s -> systemId = e.id();
                default -> {
                    // persons, objects and terminals need no lookup index beyond kind
                }
            }
        }
        indexPosition(e);
        if (e.facet() instanceof DoorFacet) {
            recomputeBlockedDoors();
        }
    }

    void setIncident(IncidentState next) {
        incident = Objects.requireNonNull(next);
    }

    private void indexPosition(Entity e) {
        if (e.kind().mobile() && e.present()) {
            String room = map.roomCodeAt(e.position());
            if (room != null) {
                occupancy.computeIfAbsent(room, k -> new TreeSet<>()).add(e.id());
            }
        }
    }

    private void unindexPosition(Entity e) {
        if (e.kind().mobile() && e.position() != null) {
            String room = map.roomCodeAt(e.position());
            TreeSet<EntityId> ids = room == null ? null : occupancy.get(room);
            if (ids != null) {
                ids.remove(e.id());
            }
        }
    }

    private void recomputeBlockedDoors() {
        long mask = 0;
        for (EntityId id : byKind.get(EntityKind.DEVICE)) {
            if (entities.get(id).facet() instanceof DoorFacet d && d.blocked()) {
                mask |= 1L << d.index();
            }
        }
        // Doors are created during genesis; until the sealed door exists as an entity the map's
        // construction-time seal still applies.
        long knownDoors = 0;
        for (EntityId id : byKind.get(EntityKind.DEVICE)) {
            if (entities.get(id).facet() instanceof DoorFacet d) {
                knownDoors |= 1L << d.index();
            }
        }
        blockedDoors = mask | (map.sealedMask() & ~knownDoors);
    }
}
