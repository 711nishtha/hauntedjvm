package hauntedjvm.core.behavior;

import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.Goal;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.Relationship;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.incident.Directive;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.random.Streams;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.DoorLayout;
import hauntedjvm.core.world.RoomLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks where a person goes next, given the state they are in.
 *
 * <p>Destinations are always room anchors or other entities' positions, which keeps the number
 * of distinct flow fields small. Every choice folds memory in: routine destinations are
 * down-weighted by how much the person dreads each room, so a single bad experience in the tape
 * archive reshapes someone's night without any rule mentioning the archive.
 */
public final class GoalPlanner {

    private static final List<String> SAFE_ROOMS = List.of("ROOM-02", "ROOM-01", "ROOM-09");

    private GoalPlanner() {
    }

    /** Whether the current goal should be replaced this tick. */
    public static boolean needsNewGoal(TickContext ctx, Entity person) {
        WorldView w = ctx.world();
        Goal g = person.mind().goal();
        long t = ctx.tick();
        if (g == null || t >= g.expiresAt()) {
            return true;
        }
        if (ctx.navigator().distance(person.position(), g.cell(), w.blockedDoors()) < 0) {
            return true;
        }
        if ((g.reason() == Goal.Reason.FOLLOW || g.reason() == Goal.Reason.ECHO) && t % 6 == 0) {
            return true;
        }
        if (person.state() == EntityState.AWARE && !g.roomCode().equals(awareRoom(w))) {
            return true;
        }
        Directive d = w.incident().directive();
        return d != null && d.activeAt(t) && d.issuedTick() == t - 1 && complies(ctx, person, d);
    }

    public static Goal plan(TickContext ctx, Entity person) {
        WorldView w = ctx.world();
        Rng rng = ctx.rng(Streams.GOAL, person.id().value());
        Directive d = w.incident().directive();
        boolean calm = person.state() == EntityState.NORMAL || person.state() == EntityState.SUSPICIOUS;
        if (calm && d != null && d.activeAt(ctx.tick()) && complies(ctx, person, d)) {
            if (d.type() == Directive.Type.GATHER) {
                return toRoom(ctx, person, d.roomCode(), Goal.Reason.GATHER, null, 30 + rng.nextInt(60), rng);
            }
            return routine(ctx, person, rng, 1.0);
        }
        return switch (person.state()) {
            case NORMAL -> routine(ctx, person, rng, 1.2);
            case SUSPICIOUS -> routine(ctx, person, rng, 2.5);
            case AFRAID -> flee(ctx, person, rng);
            case AVOIDING -> avoid(ctx, person, rng);
            case INVESTIGATING -> investigate(ctx, person, rng);
            case FOLLOWING -> follow(ctx, person, rng);
            case ECHOING -> echo(ctx, person, rng);
            case CORRUPTED -> corrupted(ctx, person, rng);
            case AWARE -> aware(ctx, person);
            default -> null;
        };
    }

    /** Deterministic per person and directive: the same broadcast gets the same answer on replay. */
    public static boolean complies(TickContext ctx, Entity person, Directive d) {
        if (!person.hasMind() || person.state().disturbed()) {
            return false;
        }
        var traits = person.mind().traits();
        Rng rng = Rng.stream(ctx.config().seed(), d.issuedTick(), Streams.DIRECTIVE, person.id().value());
        return rng.chance(0.1 + traits.obedience() * (1 - traits.paranoia() * 0.5));
    }

    private static Goal routine(TickContext ctx, Entity person, Rng rng, double dreadScale) {
        Mind mind = person.mind();
        long t = ctx.tick();
        String here = ctx.world().roomOf(person);
        double paranoia = mind.traits().paranoia();
        String room = rng.weighted(mind.role().routine(), r -> {
            double dread = Math.min(0.97, mind.dread(r, t) * (0.5 + paranoia) * dreadScale);
            return (1 - dread) * (r.equals(here) ? 0.4 : 1.0);
        });
        if (room == null) {
            room = safest(ctx.world(), mind, t);
        }
        int dwell = dreadScale > 2 ? 15 + rng.nextInt(45) : 30 + rng.nextInt(120);
        return toRoom(ctx, person, room, Goal.Reason.ROUTINE, null, dwell, rng);
    }

    private static Goal flee(TickContext ctx, Entity person, Rng rng) {
        WorldView w = ctx.world();
        Mind mind = person.mind();
        Relationship refuge = null;
        for (Relationship r : mind.relationships()) {
            Entity other = w.entity(r.other());
            if (other != null && other.present() && other.state() != EntityState.CORRUPTED
                    && (refuge == null || r.trust() > refuge.trust())) {
                refuge = r;
            }
        }
        if (refuge != null && refuge.trust() > 0.55) {
            Entity other = w.entity(refuge.other());
            String room = w.roomOf(other);
            if (room != null && mind.dread(room, ctx.tick()) < 0.5) {
                return toRoom(ctx, person, room, Goal.Reason.FLEE, other.id(), 40 + rng.nextInt(60), rng);
            }
        }
        String room = SAFE_ROOMS.stream()
                .max(Comparator.comparingDouble(r -> Perception.personsIn(w, r) - 3 * mind.dread(r, ctx.tick())))
                .orElse("ROOM-02");
        return toRoom(ctx, person, room, Goal.Reason.FLEE, null, 40 + rng.nextInt(60), rng);
    }

    private static Goal avoid(TickContext ctx, Entity person, Rng rng) {
        WorldView w = ctx.world();
        Mind mind = person.mind();
        long t = ctx.tick();
        String here = w.roomOf(person);
        if (here != null && mind.dread(here, t) > 0.2) {
            String exit = w.map().adjacentRooms(here).stream()
                    .filter(r -> listed(w, r))
                    .min(Comparator.comparingDouble(r -> mind.dread(r, t)))
                    .orElse(safest(w, mind, t));
            return toRoom(ctx, person, exit, Goal.Reason.AVOID, null, 20 + rng.nextInt(40), rng);
        }
        String room = rng.weighted(mind.role().routine(), r -> Math.pow(1 - Math.min(1, mind.dread(r, t) * 2), 4)
                * (1 + Perception.personsIn(w, r)));
        return toRoom(ctx, person, room == null ? safest(w, mind, t) : room, Goal.Reason.AVOID, null,
                20 + rng.nextInt(60), rng);
    }

    private static Goal investigate(TickContext ctx, Entity person, Rng rng) {
        MemoryTrace lead = person.mind().mostSalientFear(ctx.tick(), 0.2).orElse(null);
        if (lead == null || !listed(ctx.world(), lead.roomCode())) {
            return routine(ctx, person, rng, 1.5);
        }
        return toRoom(ctx, person, lead.roomCode(), Goal.Reason.INVESTIGATE, lead.subject(), 30 + rng.nextInt(40), rng);
    }

    private static Goal follow(TickContext ctx, Entity person, Rng rng) {
        WorldView w = ctx.world();
        Relationship lead = PersonBrain.leader(w, person).orElse(null);
        if (lead == null) {
            return routine(ctx, person, rng, 1.5);
        }
        return shadow(ctx, person, w.entity(lead.other()), Goal.Reason.FOLLOW);
    }

    private static Goal echo(TickContext ctx, Entity person, Rng rng) {
        WorldView w = ctx.world();
        Entity model = person.marks().stream()
                .filter(m -> m.startsWith(Marks.ECHO_PREFIX))
                .map(m -> w.entity(EntityId.of(Integer.parseInt(m.substring(Marks.ECHO_PREFIX.length() + 1)))))
                .filter(e -> e != null && e.present())
                .findFirst().orElse(null);
        if (model == null) {
            return routine(ctx, person, rng, 1.0);
        }
        return shadow(ctx, person, model, Goal.Reason.ECHO);
    }

    /** Goes where another entity is going; refreshed every few ticks. */
    private static Goal shadow(TickContext ctx, Entity person, Entity model, Goal.Reason reason) {
        Cell target = model.hasMind() && model.mind().goal() != null ? model.mind().goal().cell() : model.position();
        if (ctx.navigator().distance(person.position(), target, ctx.world().blockedDoors()) < 0) {
            target = model.position();
        }
        return new Goal(target, ctx.world().map().roomCodeAt(target), reason, model.id(), ctx.tick() + 8);
    }

    private static Goal corrupted(TickContext ctx, Entity person, Rng rng) {
        WorldView w = ctx.world();
        DoorLayout voidDoor = w.map().doors().stream().filter(DoorLayout::sealed).findFirst().orElse(null);
        if (voidDoor != null) {
            Entity door = w.doorAt(voidDoor.cell());
            String inside = voidDoor.roomA().equals("CORRIDOR-N") ? voidDoor.roomB() : voidDoor.roomA();
            boolean open = door != null && door.facet() instanceof DoorFacet d && !d.sealed();
            if (open && rng.chance(0.6)) {
                return toRoom(ctx, person, inside, Goal.Reason.DRAWN, null, 200 + rng.nextInt(200), rng);
            }
            if (rng.chance(0.5)) {
                // Stand in the corridor, facing the wall where the unlisted room is.
                Cell below = voidDoor.cell().offset(0, 1);
                if (w.map().passable(below, w.blockedDoors())) {
                    return new Goal(below, w.map().roomCodeAt(below), Goal.Reason.DRAWN, null,
                            ctx.tick() + 150 + rng.nextInt(150));
                }
            }
        }
        List<String> dark = new ArrayList<>();
        for (Entity room : w.ofKind(EntityKind.ROOM)) {
            if (room.facet() instanceof RoomFacet rf && rf.listed() && rf.light().dark()) {
                dark.add(rf.code());
            }
        }
        String room = dark.isEmpty() ? rng.pick(person.mind().role().routine()) : rng.pick(dark);
        return toRoom(ctx, person, room, Goal.Reason.DRAWN, null, 120 + rng.nextInt(200), rng);
    }

    private static Goal aware(TickContext ctx, Entity person) {
        WorldView w = ctx.world();
        String room = awareRoom(w);
        Cell mount = null;
        Entity camera = w.incident().observedCamera() == null ? null : w.camera(w.incident().observedCamera());
        if (camera != null && camera.facet() instanceof CameraFacet cf && room.equals(cf.roomCode())) {
            mount = cf.mount();
        }
        RoomLayout layout = w.map().room(room);
        Cell spot = layout.centre();
        if (mount != null) {
            final Cell m = mount;
            // The anchor nearest the lens, but not directly under it: they want to be seen.
            spot = layout.anchors().stream()
                    .filter(a -> a.manhattan(m) >= 2)
                    .min(Comparator.comparingInt(a -> a.manhattan(m)))
                    .orElse(layout.centre());
        }
        return new Goal(spot, room, Goal.Reason.DRAWN, null, ctx.tick() + 90);
    }

    /** Where aware minds converge: the watched room, or the security office when nothing is watched. */
    static String awareRoom(WorldView w) {
        String observed = w.observedRoom();
        return observed == null ? "ROOM-01" : observed;
    }

    private static Goal toRoom(TickContext ctx, Entity person, String room, Goal.Reason reason, EntityId target,
                               int dwell, Rng rng) {
        WorldView w = ctx.world();
        RoomLayout layout = w.map().room(room);
        List<Cell> anchors = new ArrayList<>(layout.anchors());
        Cell cell = null;
        for (int attempt = 0; attempt < anchors.size() && cell == null; attempt++) {
            Cell candidate = rng.pick(anchors);
            if (ctx.navigator().reachable(person.position(), candidate, w.blockedDoors())) {
                cell = candidate;
            }
        }
        if (cell == null) {
            // Locked out: settle for anywhere reachable nearby.
            String here = w.roomOf(person);
            RoomLayout fallback = w.map().room(here == null ? room : here);
            cell = rng.pick(fallback.anchors());
            room = fallback.code();
        }
        int travel = Math.max(0, ctx.navigator().distance(person.position(), cell, w.blockedDoors()));
        long expires = ctx.tick() + (long) travel * Locomotion.pace(person.state()) + dwell;
        return new Goal(cell, room, reason, target, expires);
    }

    private static String safest(WorldView w, Mind mind, long t) {
        return SAFE_ROOMS.stream().min(Comparator.comparingDouble(r -> mind.dread(r, t))).orElse("ROOM-02");
    }

    private static boolean listed(WorldView w, String room) {
        Entity e = room == null ? null : w.room(room);
        return e != null && e.facet() instanceof RoomFacet rf && rf.listed();
    }
}
