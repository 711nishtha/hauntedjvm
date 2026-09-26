package hauntedjvm.core.behavior;

import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.Goal;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.event.EntityEvent.AffectChanged;
import hauntedjvm.core.event.EntityEvent.EntityMoved;
import hauntedjvm.core.event.EntityEvent.EntityStateChanged;
import hauntedjvm.core.event.EntityEvent.GoalChanged;
import hauntedjvm.core.event.EntityEvent.IdentityClaimed;
import hauntedjvm.core.event.EntityEvent.TrackingChanged;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.random.Streams;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.DoorLayout;
import hauntedjvm.core.world.RoomKind;
import hauntedjvm.core.world.RoomLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The unaccounted entity. It has no script; it has preferences.
 *
 * <p>It prefers the dark, it prefers rooms nobody is watching, and it prefers people who are
 * alone. It only moves when its room is not on the operator's screen (until the facility is far
 * enough gone that it stops caring). And it can take the place of someone nobody remembers:
 * forgetting is a precondition, which the memory system produces on its own.
 */
public final class UnknownBrain {

    private static final int INFLUENCE_PERIOD = 6;
    private static final double EXPOSURE_LIGHT = 0.5;
    private static final double EXPOSURE_PER_TICK = 0.03;
    private static final double BANISH_EXPOSURE = 0.9;
    private static final long STALK_PATIENCE = 400;

    private UnknownBrain() {
    }

    public static void think(TickContext ctx, Entity self) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        int level = w.incident().level();
        Rng rng = ctx.rng(Streams.UNKNOWN, self.id().value());
        String room = w.roomOf(self);

        if (self.state() == EntityState.DORMANT) {
            if (canWake(w, self, level)) {
                transition(ctx, self, EntityState.WANDERING, "woke");
            }
            return;
        }
        if (room != null && room.equals(w.observedRoom()) && level < 5
                && Perception.light(w, room, t) >= EXPOSURE_LIGHT) {
            ctx.emit(new AffectChanged(self.id(), EXPOSURE_PER_TICK, 0, 0, "held in the light"));
            if (w.entity(self.id()).mind().fearAt(t) >= BANISH_EXPOSURE) {
                retreat(ctx, w.entity(self.id()));
                return;
            }
        }

        Entity target = self.mind().goal() == null ? null : w.entity(self.mind().goal().target());
        if (self.state() == EntityState.STALKING) {
            if (target == null || !target.present() || t - self.mind().goal().expiresAt() > STALK_PATIENCE) {
                transition(ctx, self, EntityState.WANDERING, "lost interest");
                self = w.entity(self.id());
                target = null;
            } else if (target.position().manhattan(self.position()) <= 1) {
                pressOn(ctx, self, target, room);
                self = w.entity(self.id());
                if (self.state() == EntityState.MIMICKING) {
                    return;
                }
            }
        } else if (room != null) {
            Entity prey = lonePerson(w, room);
            if (prey != null && prey.mind().fearAt(t) > 0.35 && self.state() != EntityState.MIMICKING) {
                transition(ctx, self, EntityState.STALKING, "found " + prey.name() + " alone");
                self = w.entity(self.id());
                ctx.emit(new GoalChanged(self.id(), new Goal(prey.position(), room, Goal.Reason.STALK, prey.id(), t)));
                self = w.entity(self.id());
            }
        }
        if (self.state() == EntityState.MIMICKING && t - self.mind().goal().expiresAt() > 700) {
            transition(ctx, self, EntityState.STALKING, "tired of pretending");
            self = w.entity(self.id());
        }

        replan(ctx, self, rng);
        self = w.entity(self.id());
        boolean watched = room != null && room.equals(w.observedRoom());
        if (watched && level < 5) {
            return;
        }
        int pace = room != null && Perception.light(w, room, t) < 0.35 ? 2 : 3;
        if (self.state() == EntityState.STALKING && self.mind().goal() != null) {
            Entity prey = w.entity(self.mind().goal().target());
            if (prey != null && prey.present() && room != null && room.equals(w.roomOf(prey))
                    && (t + self.id().value()) % pace == 0) {
                Cell step = Locomotion.greedyStep(w, self.position(), prey.position());
                if (step != null && !step.equals(prey.position())) {
                    Locomotion.stepTo(ctx, self, step);
                }
                return;
            }
        }
        Locomotion.advance(ctx, self, pace);
    }

    /**
     * Held on camera in a lit room long enough, it withdraws behind the sealed wall into the
     * room that is not on the plan. It stays there unless that wall ever opens.
     */
    private static void retreat(TickContext ctx, Entity self) {
        WorldView w = ctx.world();
        RoomLayout hidden = w.map().rooms().stream().filter(r -> r.kind() == RoomKind.HIDDEN).findFirst().orElse(null);
        if (hidden == null) {
            return;
        }
        if (self.impersonating()) {
            ctx.emit(new IdentityClaimed(self.id(), self.id()));
        }
        if (self.tracked()) {
            ctx.emit(new TrackingChanged(self.id(), false));
        }
        transition(ctx, self, EntityState.DORMANT, "retreated from the light");
        ctx.emit(new GoalChanged(self.id(), null));
        ctx.emit(new EntityMoved(self.id(), self.position(), hidden.centre()));
    }

    private static boolean canWake(WorldView w, Entity self, int level) {
        RoomLayout at = w.map().roomAt(self.position());
        if (at != null && at.kind() == RoomKind.HIDDEN) {
            // Banished: it can only come back out if the sealed door has been opened.
            return w.map().doors().stream().filter(DoorLayout::sealed).anyMatch(d -> {
                Entity door = w.doorAt(d.cell());
                return door != null && door.facet() instanceof DoorFacet df && !df.sealed();
            }) && self.mind().fearAt(w.tick()) < 0.3;
        }
        return level >= 2 || w.tick() - self.createdTick() > 300;
    }

    /** Standing next to someone. Every few ticks, it costs them something. */
    private static void pressOn(TickContext ctx, Entity self, Entity target, String room) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        if ((t + self.id().value()) % INFLUENCE_PERIOD != 0) {
            return;
        }
        boolean dark = room != null && Perception.light(w, room, t) < 0.35;
        Reactions.feel(ctx, target, 0.12 * (0.6 + target.mind().traits().fear()), 0.02, dark ? 0.05 : 0.035,
                "something standing close");
        target = w.entity(target.id());
        boolean alone = room != null && Perception.personsIn(w, room) == 1;
        boolean unobserved = room == null || !room.equals(w.observedRoom());
        boolean vulnerable = target.forgotten() ? target.corruption() >= 0.4 : target.corruption() >= 0.8;
        if (alone && unobserved && vulnerable && (dark || w.incident().level() >= 4)) {
            take(ctx, self, target, room);
        }
    }

    /**
     * It takes their place. The person goes missing; their tracker badge keeps reporting from
     * where they were last seen, and something else begins answering to their name.
     */
    private static void take(TickContext ctx, Entity self, Entity victim, String room) {
        ctx.emit(new EntityStateChanged(victim.id(), victim.state(), EntityState.MISSING,
                "last seen in " + room));
        ctx.emit(new IdentityClaimed(self.id(), victim.id()));
        ctx.emit(new TrackingChanged(self.id(), true));
        transition(ctx, ctx.world().entity(self.id()), EntityState.MIMICKING, "answering to " + victim.name());
        Entity now = ctx.world().entity(self.id());
        ctx.emit(new GoalChanged(self.id(), new Goal(now.position(), room, Goal.Reason.IMPERSONATE, victim.id(),
                ctx.tick())));
    }

    private static void replan(TickContext ctx, Entity self, Rng rng) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        Goal g = self.mind().goal();
        boolean stale = g == null || (g.reason() == Goal.Reason.WANDER && t >= g.expiresAt())
                || (g.reason() == Goal.Reason.IMPERSONATE && self.position().equals(g.cell()));
        if (self.state() == EntityState.STALKING && g != null && g.target() != null) {
            Entity prey = w.entity(g.target());
            if (prey != null && prey.present() && t % 5 == 0) {
                ctx.emit(new GoalChanged(self.id(), new Goal(nearestAnchor(w, prey.position()), w.roomOf(prey),
                        Goal.Reason.STALK, prey.id(), g.expiresAt())));
            }
            return;
        }
        if (!stale) {
            return;
        }
        Goal next;
        if (self.state() == EntityState.MIMICKING) {
            Entity victim = w.entity(self.identity());
            List<String> routine = victim != null && victim.hasMind() && victim.mind().role() != null
                    ? victim.mind().role().routine() : List.of("CORRIDOR-N", "CORRIDOR-S");
            String room = rng.pick(routine);
            long since = g == null ? t : g.expiresAt();
            next = new Goal(rng.pick(w.map().room(room).anchors()), room, Goal.Reason.IMPERSONATE, self.identity(),
                    since);
        } else {
            String room = chooseRoom(w, rng);
            next = new Goal(rng.pick(w.map().room(room).anchors()), room, Goal.Reason.WANDER, null,
                    t + 120 + rng.nextInt(240));
        }
        if (ctx.navigator().reachable(self.position(), next.cell(), w.blockedDoors())) {
            ctx.emit(new GoalChanged(self.id(), next));
        }
    }

    private static String chooseRoom(WorldView w, Rng rng) {
        List<String> rooms = new ArrayList<>();
        for (Entity e : w.ofKind(EntityKind.ROOM)) {
            if (e.facet() instanceof RoomFacet rf && rf.listed()) {
                rooms.add(rf.code());
            }
        }
        String observed = w.observedRoom();
        long t = w.tick();
        return rng.weighted(rooms, r -> {
            double weight = 1;
            if (Perception.light(w, r, t) < 0.35) {
                weight *= 3;
            }
            if (!r.equals(observed)) {
                weight *= 2;
            }
            int people = Perception.personsIn(w, r);
            if (people == 1) {
                Entity lone = lonePerson(w, r);
                weight *= lone != null && lone.forgotten() ? 12 : 4;
            } else if (people > 2) {
                weight *= 0.3;
            }
            return weight;
        });
    }

    private static Entity lonePerson(WorldView w, String room) {
        Entity found = null;
        for (Entity e : w.occupants(room)) {
            if (e.kind() == EntityKind.PERSON) {
                if (found != null) {
                    return null;
                }
                found = e;
            }
        }
        return found;
    }

    private static Cell nearestAnchor(WorldView w, Cell near) {
        RoomLayout room = w.map().roomAt(near);
        if (room == null) {
            return near;
        }
        Cell best = room.anchors().getFirst();
        for (Cell a : room.anchors()) {
            if (a.manhattan(near) < best.manhattan(near)) {
                best = a;
            }
        }
        return best;
    }

    private static void transition(TickContext ctx, Entity self, EntityState to, String reason) {
        if (self.state() != to) {
            ctx.emit(new EntityStateChanged(self.id(), self.state(), to, reason));
        }
    }
}
