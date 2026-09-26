package hauntedjvm.core.anomaly;

import hauntedjvm.core.behavior.Reactions;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.event.EntityEvent.MarkChanged;
import hauntedjvm.core.event.EntityEvent.MemoryFaded;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.incident.ScheduledPerturbation;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.state.WorldView;
import java.util.List;

/** Interference with memory and behaviour. */
final class MindPerturbations {

    private static final List<String> IMPLANTS = List.of(
            "a door in %s that was not there before",
            "someone standing very still in %s",
            "the lights in %s going out one by one",
            "%s, but the room was longer than it should be",
            "a voice on the intercom in %s saying their name");

    private MindPerturbations() {
    }

    /**
     * Implants a memory of something that never happened. Its source pointer is aimed at a real
     * but unrelated event, the way a false memory borrows the texture of a true one.
     */
    static final class FalseMemory implements Perturbation {
        @Override
        public String name() {
            return "false-memory";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.MEMORY;
        }

        @Override
        public int minLevel() {
            return 1;
        }

        @Override
        public double weight(WorldView w) {
            return Director.presentPersons(w).isEmpty() ? 0 : 1.0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            long t = ctx.tick();
            Entity person = rng.weighted(Director.presentPersons(w),
                    e -> 0.3 + e.mind().awarenessAt(t) + e.mind().traits().paranoia());
            if (person == null || ctx.log().size() < 10) {
                return false;
            }
            String room = rng.pick(Director.listedRooms(w));
            long borrowed = rng.nextInt(ctx.log().size() - 1);
            String note = String.format(rng.pick(IMPLANTS), room);
            Director.announce(ctx, this, List.of(person.id()), room, note);
            Reactions.remember(ctx, person.id(), new MemoryTrace(0, t - rng.nextInt(1, 240), MemoryKind.SAW_ANOMALY,
                    null, room, null, 0.8, -0.7, borrowed, note));
            Reactions.feel(ctx, person, 0.12, 0.05, 0.02, "remembered something");
            return true;
        }
    }

    /**
     * Wipes a person from everyone else's memory, and schedules their disappearance. Whether
     * the disappearance happens depends on whether anyone is watching when it comes due.
     */
    static final class MemoryErasure implements Perturbation {
        @Override
        public String name() {
            return "memory-erasure";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.MEMORY;
        }

        @Override
        public int minLevel() {
            return 2;
        }

        @Override
        public double weight(WorldView w) {
            return Director.presentPersons(w).size() > 3 ? 0.7 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            long t = ctx.tick();
            List<Entity> persons = Director.presentPersons(w);
            // The least connected are the easiest to erase.
            Entity victim = rng.weighted(persons, e -> 1.0 / (1 + e.mind().relationships().stream()
                    .mapToDouble(r -> r.familiarity()).sum()));
            if (victim == null) {
                return false;
            }
            Director.announce(ctx, this, List.of(victim.id()), w.roomOf(victim), victim.name());
            int erased = 0;
            for (Entity other : persons) {
                for (MemoryTrace m : other.mind().memories()) {
                    if (erased < 16 && victim.id().equals(m.subject()) && m.tick() <= t) {
                        ctx.emit(new MemoryFaded(other.id(), m.id()));
                        erased++;
                    }
                }
            }
            Director.schedule(ctx, IdentityPerturbations.VANISHING, 200 + rng.nextInt(300), List.of(victim.id()),
                    null, "after erasure");
            return true;
        }
    }

    /** One person starts moving like another, then stops. */
    static final class BehaviorEcho implements Perturbation {
        @Override
        public String name() {
            return "behavior-echo";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.BEHAVIORAL;
        }

        @Override
        public int minLevel() {
            return 2;
        }

        @Override
        public double weight(WorldView w) {
            return Director.presentPersons(w).size() >= 2 ? 0.8 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            List<Entity> persons = Director.presentPersons(ctx.world());
            Entity echo = rng.pick(persons);
            Entity model = rng.pick(persons);
            if (echo.id().equals(model.id()) || echo.marks().stream().anyMatch(m -> m.startsWith(Marks.ECHO_PREFIX))) {
                return false;
            }
            String mark = Marks.ECHO_PREFIX + model.id();
            Director.announce(ctx, this, List.of(echo.id(), model.id()), ctx.world().roomOf(echo), mark);
            ctx.emit(new MarkChanged(echo.id(), mark, true));
            Director.schedule(ctx, this, 150 + rng.nextInt(250), List.of(echo.id()), null, mark);
            return true;
        }

        @Override
        public void resume(TickContext ctx, ScheduledPerturbation step, Rng rng) {
            EntityId echo = step.targets().getFirst();
            Entity e = ctx.world().entity(echo);
            if (e != null && e.hasMark(step.detail())) {
                ctx.emit(new MarkChanged(echo, step.detail(), false));
            }
        }
    }

    /** Whoever is on screen now stops moving while watched. */
    static final class ObserverEffect implements Perturbation {
        @Override
        public String name() {
            return "observer-effect";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.OBSERVATION;
        }

        @Override
        public int minLevel() {
            return 3;
        }

        @Override
        public double weight(WorldView w) {
            String room = w.observedRoom();
            return room != null && !w.occupants(room).isEmpty() ? 1.2 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            String room = w.observedRoom();
            List<Entity> watched = w.occupants(room).stream()
                    .filter(e -> e.kind() == EntityKind.PERSON && !e.hasMark(Marks.OBSERVER_SENSITIVE)).toList();
            if (watched.isEmpty()) {
                return false;
            }
            List<EntityId> ids = watched.stream().map(Entity::id).toList();
            Director.announce(ctx, this, ids, room, "held while watched");
            for (EntityId id : ids) {
                ctx.emit(new MarkChanged(id, Marks.OBSERVER_SENSITIVE, true));
            }
            Director.schedule(ctx, this, 240 + rng.nextInt(240), ids, room, "release");
            return true;
        }

        @Override
        public void resume(TickContext ctx, ScheduledPerturbation step, Rng rng) {
            for (EntityId id : step.targets()) {
                Entity e = ctx.world().entity(id);
                if (e != null && e.hasMark(Marks.OBSERVER_SENSITIVE)) {
                    ctx.emit(new MarkChanged(id, Marks.OBSERVER_SENSITIVE, false));
                }
            }
        }
    }
}
