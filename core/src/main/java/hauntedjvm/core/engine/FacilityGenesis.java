package hauntedjvm.core.engine;

import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.Decaying;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.ItemFacet;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.entity.Relationship;
import hauntedjvm.core.entity.Role;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.entity.SystemFacet;
import hauntedjvm.core.entity.TerminalFacet;
import hauntedjvm.core.entity.Traits;
import hauntedjvm.core.event.EntityEvent.EntityCreated;
import hauntedjvm.core.event.EntityEvent.MemoryFormed;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.random.Streams;
import hauntedjvm.core.world.CameraMount;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.DoorLayout;
import hauntedjvm.core.world.FacilityMap;
import hauntedjvm.core.world.RoomKind;
import hauntedjvm.core.world.RoomLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Populates an empty world at tick 0. Genesis is itself a sequence of events, so the very first
 * state of every run is reproducible from the log like any other.
 */
final class FacilityGenesis {

    static final String ORIGIN_ROSTER = "ROSTER";
    static final String ORIGIN_SYSTEM = "SYSTEM";

    private static final List<String> NAMES = List.of(
            "MARA", "ELIAS", "TOVE", "JONAH", "IRIS", "OSKAR", "NELL", "AUGUST", "RUTH", "SILAS",
            "VERA", "TOBIAS", "EDITH", "CASPER", "LENA", "MILO", "HANNA", "FELIX", "GRETA", "ARNE",
            "INGRID", "LUDVIG", "SAGA", "OTTO", "MAJA", "EMIL", "ASTRID", "VIKTOR", "SIGNE", "HUGO",
            "ALMA", "KARL", "FREJA", "BRUNO", "LIV", "AXEL", "DAGNY", "ERIK", "WILMA", "SVEN");

    private record ProcessSpec(String command, String host) {
    }

    private static final List<ProcessSpec> PROCESSES = List.of(
            new ProcessSpec("badge-trackd", "srv-hall-1"),
            new ProcessSpec("cam-mux", "srv-hall-2"),
            new ProcessSpec("lumen-ctl", "srv-hall-3"),
            new ProcessSpec("watchdog", "srv-hall-1"),
            new ProcessSpec("incident-log", "srv-hall-4"),
            new ProcessSpec("tape-indexer", "archive-1"),
            new ProcessSpec("archive-sync", "records-1"),
            new ProcessSpec("hvac-ctl", "gen-ctl-1"));

    private record ItemSpec(String name, String description, String room) {
    }

    private static final List<ItemSpec> ITEMS = List.of(
            new ItemSpec("REEL-7", "reel-to-reel tape; the label reads DO NOT INDEX", "ROOM-04"),
            new ItemSpec("LOGBOOK", "night-shift logbook", "ROOM-01"),
            new ItemSpec("RADIO", "handheld radio, left on channel 4", "ROOM-02"),
            new ItemSpec("PHOTOGRAPH", "staff photograph, one face scratched out", "ROOM-05"),
            new ItemSpec("WHEELCHAIR", "folded wheelchair", "ROOM-10"),
            new ItemSpec("MANNEQUIN", "CPR training mannequin", "ROOM-11"),
            new ItemSpec("CRATE-19", "shipping crate, never opened", "ROOM-12"));

    private static final Map<String, String> HOST_PREFIX = Map.of(
            "ROOM-01", "sec", "ROOM-03", "srv-hall", "ROOM-04", "archive", "ROOM-05", "records",
            "ROOM-06", "gen-ctl", "ROOM-07", "lab-a", "ROOM-08", "lab-b", "ROOM-10", "infirm", "ROOM-11", "stores");

    private final TickContext ctx;
    private final FacilityMap map;
    private final Rng rng;
    private int serial;

    private FacilityGenesis(TickContext ctx) {
        this.ctx = ctx;
        this.map = ctx.world().map();
        this.rng = Rng.stream(ctx.config().seed(), 0, Streams.GENESIS, 0);
    }

    static void populate(TickContext ctx) {
        new FacilityGenesis(ctx).run();
    }

    private void run() {
        create(EntityKind.SYSTEM, "FACILITY-07/CTL", null, EntityState.PRESENT, false, new SystemFacet("FACILITY-07"),
                ORIGIN_SYSTEM);
        List<Entity> persons = createPersons();
        createRooms();
        createDoors();
        createCameras();
        createTerminals();
        createProcesses();
        createItems();
        seedPreviousShift(persons);
    }

    private List<Entity> createPersons() {
        int count = ctx.config().persons();
        int firstSerial = serial;
        List<String> names = new ArrayList<>(NAMES);
        for (int i = names.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            String tmp = names.get(i);
            names.set(i, names.get(j));
            names.set(j, tmp);
        }
        Role[] roles = Role.values();
        int roleOffset = rng.nextInt(roles.length);
        List<Entity> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Role role = roles[(i + roleOffset) % roles.length];
            Traits traits = Traits.random(rng);
            String name = i < names.size() ? names.get(i) : names.get(i % names.size()) + "-" + (i / names.size() + 1);
            List<Relationship> relations = new ArrayList<>();
            int links = Math.min(count - 1, 2 + rng.nextInt(4));
            Set<Integer> chosen = new HashSet<>();
            for (int k = 0; k < links; k++) {
                int other = rng.nextInt(count);
                if (other == i || !chosen.add(other)) {
                    continue;
                }
                Role otherRole = roles[(other + roleOffset) % roles.length];
                double familiarity = rng.bell(0.45, 0.2) + (otherRole == role ? 0.2 : 0.0);
                relations.add(new Relationship(EntityId.of(firstSerial + other), rng.bell(0.55, 0.2), familiarity));
            }
            relations.sort((a, b) -> a.other().compareTo(b.other()));
            Mind mind = new Mind(role, traits,
                    Decaying.at(0.04 + 0.08 * traits.fear(), 0, 180),
                    Decaying.at(0.15 * traits.awareness(), 0, 1500),
                    List.of(), relations, null);
            String room = rng.pick(role.routine());
            Cell start = rng.pick(map.room(room).anchors());
            created.add(create(EntityKind.PERSON, name, start, EntityState.NORMAL, true, mind, ORIGIN_ROSTER));
        }
        return created;
    }

    private void createRooms() {
        String dimRoom = rng.chance(0.4) ? rng.pick(List.of("ROOM-04", "ROOM-05", "ROOM-08", "ROOM-09")) : null;
        for (RoomLayout r : map.rooms()) {
            LightMode normal = switch (r.code()) {
                case "ROOM-06", "ROOM-11", "ROOM-12" -> LightMode.DIM;
                default -> r.kind() == RoomKind.HIDDEN ? LightMode.OFF
                        : r.code().equals(dimRoom) ? LightMode.DIM : LightMode.ON;
            };
            create(EntityKind.ROOM, r.code(), r.centre(), EntityState.PRESENT, false,
                    new RoomFacet(r.code(), r.label(), normal, normal, -1, r.listed()), ORIGIN_SYSTEM);
        }
    }

    private void createDoors() {
        boolean lockStorage = rng.chance(0.35);
        for (DoorLayout d : map.doors()) {
            boolean locked = lockStorage && d.connects("ROOM-11");
            create(EntityKind.DEVICE, d.code(), d.cell(), EntityState.ONLINE, false,
                    new DoorFacet(d.code(), d.index(), false, locked, d.sealed(), -1), ORIGIN_SYSTEM);
        }
    }

    private void createCameras() {
        for (CameraMount m : map.cameras()) {
            if (!m.hidden()) {
                create(EntityKind.CAMERA, m.code(), m.mount(), EntityState.ONLINE, false,
                        new CameraFacet(m.code(), m.roomCode(), m.mount(), CameraStatus.ONLINE, 0, -1), ORIGIN_SYSTEM);
            }
        }
    }

    private void createTerminals() {
        for (RoomLayout r : map.rooms()) {
            int n = 1;
            for (Cell t : r.terminals()) {
                String host = HOST_PREFIX.getOrDefault(r.code(), r.code().toLowerCase()) + "-" + n++;
                create(EntityKind.DEVICE, "TERM " + host, t, EntityState.ONLINE, false, new TerminalFacet(r.code(), host),
                        ORIGIN_SYSTEM);
            }
        }
    }

    private void createProcesses() {
        Set<Integer> pids = new HashSet<>();
        for (ProcessSpec p : PROCESSES) {
            int pid;
            do {
                pid = 100 + rng.nextInt(3900);
            } while (!pids.add(pid));
            long heap = (8L + rng.nextInt(56)) << 20;
            create(EntityKind.PROCESS, p.command(), null, EntityState.RUNNING, false,
                    new ProcessFacet(pid, pid, p.command(), p.host(), heap, 0, -1, 0, 0, null), ORIGIN_SYSTEM);
        }
    }

    private void createItems() {
        for (ItemSpec item : ITEMS) {
            RoomLayout room = map.room(item.room());
            Cell at;
            do {
                at = rng.pick(room.cells());
            } while (room.terminals().contains(at));
            create(EntityKind.OBJECT, item.name(), at, EntityState.PRESENT, false, new ItemFacet(item.description(), at),
                    ORIGIN_SYSTEM);
        }
    }

    /**
     * Very rarely, someone arrives for the shift remembering a colleague who is not on this
     * roster. The memory has no cause in this run's history, and the detector will notice.
     */
    private void seedPreviousShift(List<Entity> persons) {
        Rng rare = Rng.stream(ctx.config().seed(), 0, Streams.RARE, 1);
        if (persons.isEmpty() || !rare.chance(0.04 * ctx.config().rareEventRate())) {
            return;
        }
        Entity who = rare.pick(persons);
        EntityId stranger = EntityId.of(serial + 900 + rare.nextInt(90));
        String strangerName = NAMES.get(rare.nextInt(NAMES.size()));
        String room = map.roomCodeAt(who.position());
        ctx.emit(new MemoryFormed(who.id(), new MemoryTrace(0, 0, MemoryKind.SAW_ENTITY, stranger, room, null,
                0.95, 0.4, -1, "worked last night's shift with " + strangerName + " " + stranger)));
    }

    private Entity create(EntityKind kind, String name, Cell position, EntityState state, boolean tracked,
                          hauntedjvm.core.entity.Facet facet, String origin) {
        EntityId id = EntityId.of(serial++);
        Entity e = new Entity(id, uuid(rng), kind, name, position, tracked ? position : null, tracked, false, state, id,
                0.0, false, 0, List.of(), facet);
        ctx.emit(new EntityCreated(e, origin));
        return e;
    }

    /** Version-4 shaped UUIDs drawn from the seeded stream, so identities are reproducible too. */
    static UUID uuid(Rng rng) {
        long msb = (rng.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        long lsb = (rng.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
