package hauntedjvm.core.behavior;

import hauntedjvm.core.engine.SimulationSystem;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.Traits;
import hauntedjvm.core.event.EntityEvent.EntityObserved;
import hauntedjvm.core.event.EntityEvent.RelationshipChanged;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.random.Streams;
import hauntedjvm.core.state.WorldView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What people notice. Everything a mind knows about the world arrives through here, through
 * conversation, or through the anomaly engine putting it there.
 *
 * <p>Every perceived memory points at an {@link EntityObserved} event, never directly at the
 * thing seen. The observation is the evidence; whether it was accurate is a separate question
 * the detector is free to ask.
 */
public final class PerceptionSystem implements SimulationSystem {

    private static final long RECALL_PERSON = 240;
    private static final long RECALL_UNKNOWN = 40;
    /** Each pair of people gets a chance to register each other once per this many ticks. */
    private static final int PERSON_PAIR_PERIOD = 45;
    private static final int UNKNOWN_PAIR_PERIOD = 8;
    private static final long RECALL_OBJECT = 500;
    private static final int AMBIENT_PERIOD = 10;
    private static final int OBJECT_PERIOD = 5;

    @Override
    public String name() {
        return "perception";
    }

    /**
     * Who is in a room this tick, with people pre-bucketed by when they are due to be noticed.
     *
     * <p>A pair (a, b) is considered when {@code (t + 7a + 13b) mod P == 0}. Because 13 is
     * invertible mod P, that condition picks out exactly one residue class of {@code 13b mod P},
     * so an observer only has to visit one bucket instead of everyone in the room. Crowded rooms
     * drop from quadratic to roughly linear work, with identical outcomes.
     */
    private static final class Crowd {
        final List<Entity> unknowns = new ArrayList<>();
        final List<List<Entity>> due = new ArrayList<>(PERSON_PAIR_PERIOD);
        int persons;

        Crowd(List<Entity> occupants) {
            for (int i = 0; i < PERSON_PAIR_PERIOD; i++) {
                due.add(List.of());
            }
            for (Entity e : occupants) {
                if (e.kind() == EntityKind.UNKNOWN) {
                    unknowns.add(e);
                } else if (e.kind() == EntityKind.PERSON) {
                    persons++;
                    int key = Math.floorMod(e.id().value() * 13L, PERSON_PAIR_PERIOD);
                    if (due.get(key).isEmpty()) {
                        due.set(key, new ArrayList<>());
                    }
                    due.get(key).add(e);
                }
            }
        }

        List<Entity> dueFor(Entity observer, long t) {
            return due.get(Math.floorMod(-(t + observer.id().value() * 7L), PERSON_PAIR_PERIOD));
        }
    }

    @Override
    public void tick(TickContext ctx) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        List<Entity> objects = w.ofKind(EntityKind.OBJECT);
        // Nobody moves during perception, so rooms can be indexed once per tick.
        Map<String, Crowd> crowds = new HashMap<>();
        for (Entity person : w.ofKind(EntityKind.PERSON)) {
            if (!person.present()) {
                continue;
            }
            String room = w.roomOf(person);
            if (room == null) {
                continue;
            }
            Crowd crowd = crowds.computeIfAbsent(room, r -> new Crowd(w.occupants(r)));
            Rng rng = ctx.rng(Streams.PERCEPTION, person.id().value());
            double light = Perception.light(w, room, t);
            for (Entity other : crowd.unknowns) {
                perceive(ctx, w.entity(person.id()), other, room, light, rng);
            }
            // Attention is finite: in a crowd, someone registers at most one new face per tick.
            // With a normal night shift fewer than one pair is due per tick, so this never binds.
            long before = ctx.log().nextSeq();
            for (Entity other : crowd.dueFor(person, t)) {
                if (!other.id().equals(person.id())) {
                    perceive(ctx, w.entity(person.id()), other, room, light, rng);
                    if (ctx.log().nextSeq() != before) {
                        break;
                    }
                }
            }
            if ((t + person.id().value()) % OBJECT_PERIOD == 0) {
                for (Entity object : objects) {
                    if (object.position() != null && room.equals(w.roomOf(object))) {
                        noticeObject(ctx, w.entity(person.id()), object, room, light);
                    }
                }
            }
            if ((t + person.id().value()) % AMBIENT_PERIOD == 0) {
                ambient(ctx, w.entity(person.id()), room, light, crowd.persons - 1, !crowd.unknowns.isEmpty());
            }
        }
    }

    private void perceive(TickContext ctx, Entity person, Entity other, String room, double light, Rng rng) {
        long t = ctx.tick();
        Mind mind = person.mind();
        Traits traits = mind.traits();
        if (other.kind() == EntityKind.UNKNOWN) {
            if (!pairDue(person, other, t, UNKNOWN_PAIR_PERIOD)
                    || Perception.recentlySaw(mind, other.identity(), t, RECALL_UNKNOWN)
                    || !rng.chance(0.2 + 0.75 * light)) {
                return;
            }
            EventRecord seen = ctx.emit(new EntityObserved(person.id(), other.id(), room));
            Entity claimed = ctx.world().entity(other.identity());
            if (other.impersonating() && claimed != null) {
                double familiarity = mind.relationship(claimed.id()).map(r -> r.familiarity()).orElse(0.0);
                if (familiarity >= 0.35 || rng.chance(traits.awareness() * 0.5)) {
                    Reactions.rememberSeeing(ctx, person, claimed.id(), room, null, MemoryKind.SAW_ANOMALY, 0.9, -0.9,
                            seen.seq(), "saw " + claimed.name() + " in " + room + ". it was not " + claimed.name());
                    Reactions.feel(ctx, person, 0.3 + 0.3 * traits.fear(), 0.12, 0.03, "recognised the impostor");
                } else {
                    // The disguise holds: they believe they saw a colleague.
                    Reactions.rememberSeeing(ctx, person, claimed.id(), room, null, MemoryKind.SAW_ENTITY, 0.5, 0.1,
                            seen.seq(), "saw " + claimed.name() + " in " + room);
                }
                return;
            }
            Reactions.rememberSeeing(ctx, person, other.id(), room, null, MemoryKind.SAW_ENTITY, 0.9, -0.9, seen.seq(),
                    "a figure in " + room + ". not staff");
            Reactions.feel(ctx, person, 0.22 + 0.3 * traits.fear(), 0.07 * (0.5 + traits.awareness()),
                    light < 0.35 ? 0.02 : 0.0, "saw the figure");
            return;
        }
        if (other.kind() != EntityKind.PERSON || !pairDue(person, other, t, PERSON_PAIR_PERIOD)
                || Perception.recentlySaw(mind, other.id(), t, RECALL_PERSON)) {
            return;
        }
        EventRecord seen = ctx.emit(new EntityObserved(person.id(), other.id(), room));
        double familiarity = mind.relationship(other.id()).map(r -> r.familiarity()).orElse(0.0);
        if (other.state() == EntityState.CORRUPTED || other.state() == EntityState.AWARE
                || other.state() == EntityState.ECHOING) {
            Reactions.rememberSeeing(ctx, person, other.id(), room, null, MemoryKind.SAW_ANOMALY, 0.6, -0.5, seen.seq(),
                    other.name() + " was not right");
            Reactions.feel(ctx, person, 0.1 * (0.5 + traits.fear()), 0.03, 0, "unsettled by " + other.name());
        } else if (other.impersonating() && familiarity > 0.3) {
            Entity claimed = ctx.world().entity(other.identity());
            String claimedName = claimed == null ? other.identity().toString() : claimed.name();
            Reactions.rememberSeeing(ctx, person, other.id(), room, null, MemoryKind.SAW_ANOMALY, 0.7, -0.6, seen.seq(),
                    other.name() + " answered to " + claimedName);
            Reactions.feel(ctx, person, 0.15, 0.06, 0, "name confusion");
        } else {
            double trust = mind.relationship(other.id()).map(r -> r.trust()).orElse(0.4);
            Reactions.rememberSeeing(ctx, person, other.id(), room, null, MemoryKind.SAW_ENTITY,
                    0.3 + 0.4 * familiarity, 0.2 * trust, seen.seq(), "saw " + other.name() + " in " + room);
        }
        ctx.emit(new RelationshipChanged(person.id(), other.id(), 0.01, 0.05));
    }

    /**
     * Pairs are considered on a staggered schedule rather than every tick. Relying on memory
     * alone to suppress repeats fails in crowds: once a room holds more people than a mind has
     * memory slots, old sightings are evicted and everyone re-notices everyone, every tick.
     */
    private static boolean pairDue(Entity a, Entity b, long t, int period) {
        return Math.floorMod(t + a.id().value() * 7L + b.id().value() * 13L, period) == 0;
    }

    private void noticeObject(TickContext ctx, Entity person, Entity object, String room, double light) {
        if (light < 0.2) {
            return;
        }
        long t = ctx.tick();
        Mind mind = person.mind();
        MemoryTrace known = Perception.lastKnownLocation(mind, object.id());
        boolean moved = known != null && known.cell() != null && !known.cell().equals(object.position())
                && known.strengthAt(t, mind.traits().memoryHalfLife()) > 0.2;
        if (!moved && known != null && t - known.tick() < RECALL_OBJECT) {
            return;
        }
        EventRecord seen = ctx.emit(new EntityObserved(person.id(), object.id(), room));
        if (moved) {
            Reactions.rememberSeeing(ctx, person, object.id(), room, object.position(), MemoryKind.SAW_ANOMALY, 0.6,
                    -0.4, seen.seq(), object.name() + " is not where it was");
            Reactions.feel(ctx, person, 0.08 * (0.5 + mind.traits().fear()), 0.04, 0, "moved object");
        }
        Reactions.rememberSeeing(ctx, person, object.id(), room, object.position(), MemoryKind.OBJECT_LOCATION, 0.6, 0,
                seen.seq(), object.name() + " in " + room);
    }

    private void ambient(TickContext ctx, Entity person, String room, double light, int others, boolean unknownHere) {
        WorldView w = ctx.world();
        Traits traits = person.mind().traits();
        int level = w.incident().level();
        int companions = Math.max(0, others);

        double fear = 0;
        if (light < 0.35) {
            fear += 0.03 * (0.5 + traits.fear());
        }
        fear -= 0.015 * Math.min(3, companions);
        if (companions == 0 && level >= 2) {
            fear += 0.01 * traits.paranoia();
        }
        double awareness = level >= 3 ? 0.002 * traits.awareness() : 0;
        if (level >= 3 && room.equals(w.observedRoom())) {
            // By now they can feel the camera. Watching someone keeps them safe from some things
            // and makes them aware of others.
            awareness += 0.004 * (0.5 + traits.awareness());
        }
        double corruption = 0;
        if (unknownHere && light < 0.35) {
            corruption += 0.015;
        }
        if (person.mind().memories().stream().anyMatch(m -> m.kind() == MemoryKind.PREVIOUS_TIMELINE)) {
            corruption += 0.004;
        }
        if (person.mind().fearAt(ctx.tick()) <= 0.0 && fear < 0) {
            fear = 0;
        }
        Reactions.feel(ctx, person, fear, awareness, corruption, "ambient");
    }
}
