package hauntedjvm.core.anomaly;

import hauntedjvm.core.behavior.Perception;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Decaying;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.Traits;
import hauntedjvm.core.event.EntityEvent.BadgeReported;
import hauntedjvm.core.event.EntityEvent.EntityCreated;
import hauntedjvm.core.event.EntityEvent.EntityStateChanged;
import hauntedjvm.core.event.EntityEvent.IdentityClaimed;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.ScheduledPerturbation;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.RoomLayout;
import java.util.List;
import java.util.UUID;

/** Who is where, and who is who. */
final class IdentityPerturbations {

    static final Vanishing VANISHING = new Vanishing();

    /** Origin recorded on the unaccounted entity's creation event. */
    static final String ORIGIN_NONE = "NO RECORD";

    private IdentityPerturbations() {
    }

    /** A badge reports a position its wearer is not at. */
    static final class BadgeGhost implements Perturbation {
        @Override
        public String name() {
            return "badge-ghost";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.SPATIAL;
        }

        @Override
        public int minLevel() {
            return 1;
        }

        @Override
        public double weight(WorldView w) {
            return w.trackerOnline() ? 0.9 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            List<Entity> tracked = Director.presentPersons(w).stream().filter(e -> e.tracked() && !e.badgeOverride())
                    .toList();
            if (tracked.isEmpty()) {
                return false;
            }
            Entity p = rng.pick(tracked);
            String ghostRoom = rng.pick(Director.listedRooms(w));
            Cell ghost = rng.pick(w.map().room(ghostRoom).anchors());
            Director.announce(ctx, this, List.of(p.id()), ghostRoom, p.name() + " reported in " + ghostRoom);
            ctx.emit(new BadgeReported(p.id(), ghost));
            Director.schedule(ctx, this, 60 + rng.nextInt(200), List.of(p.id()), null, "restore");
            return true;
        }

        @Override
        public void resume(TickContext ctx, ScheduledPerturbation step, Rng rng) {
            Entity p = ctx.world().entity(step.targets().getFirst());
            if (p != null && p.badgeOverride() && p.state() != EntityState.MISSING) {
                ctx.emit(new BadgeReported(p.id(), null));
            }
        }
    }

    /**
     * Something with no personnel record appears in a dark, unwatched room. Its creation record
     * claims a time before the facility's records begin.
     */
    static final class Manifestation implements Perturbation {
        @Override
        public String name() {
            return "manifestation";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.IDENTITY;
        }

        @Override
        public int minLevel() {
            return 2;
        }

        @Override
        public double weight(WorldView w) {
            long present = w.ofKind(EntityKind.UNKNOWN).stream().filter(Entity::present).count();
            if (present >= 2 || (present == 1 && w.incident().level() < 4)) {
                return 0;
            }
            return 0.6 + 0.3 * w.incident().level();
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            List<String> quiet = Director.unwitnessedRooms(w);
            String room = rng.weighted(quiet, r -> Perception.light(w, r, ctx.tick()) < 0.35 ? 4 : 1);
            if (room == null) {
                return false;
            }
            RoomLayout layout = w.map().room(room);
            Cell at = rng.pick(layout.anchors());
            EntityId id = EntityId.of(w.entities().stream().mapToInt(e -> e.id().value()).max().orElse(0) + 1);
            Traits traits = new Traits(0.9, 0.0, 0.8, 0.0, 0.0, 1.0, 1.0);
            // Its 'fear' is exposure: how long it has been held in the light on the operator's screen.
            Mind mind = new Mind(null, traits, Decaying.at(0, ctx.tick(), 400), Decaying.at(1, ctx.tick(), 60),
                    List.of(), List.of(), null);
            Entity unknown = new Entity(id, new UUID(rng.nextLong(), rng.nextLong()), EntityKind.UNKNOWN,
                    "UNACCOUNTED", at, null, false, false, EntityState.DORMANT, id, 1.0, false, ctx.tick(), List.of(),
                    mind);
            Director.announce(ctx, this, List.of(id), room, "manifested");
            ctx.emitDated(new EntityCreated(unknown, ORIGIN_NONE), -(1 + rng.nextInt(90_000)));
            return true;
        }
    }

    /** Someone starts answering to a colleague's name, and the badge system agrees with them. */
    static final class DuplicateIdentity implements Perturbation {
        @Override
        public String name() {
            return "duplicate-identity";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.IDENTITY;
        }

        @Override
        public int minLevel() {
            return 3;
        }

        @Override
        public double weight(WorldView w) {
            return Director.presentPersons(w).size() >= 2 ? 0.6 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            List<Entity> persons = Director.presentPersons(ctx.world());
            Entity a = rng.pick(persons);
            Entity b = rng.pick(persons);
            if (a.id().equals(b.id()) || a.impersonating()) {
                return false;
            }
            Director.announce(ctx, this, List.of(a.id(), b.id()), ctx.world().roomOf(a), a.name() + " as " + b.name());
            ctx.emit(new IdentityClaimed(a.id(), b.id()));
            Director.schedule(ctx, this, 150 + rng.nextInt(300), List.of(a.id()), null, "restore");
            return true;
        }

        @Override
        public void resume(TickContext ctx, ScheduledPerturbation step, Rng rng) {
            Entity a = ctx.world().entity(step.targets().getFirst());
            if (a != null && a.kind() == EntityKind.PERSON && a.impersonating()) {
                ctx.emit(new IdentityClaimed(a.id(), a.id()));
            }
        }
    }

    /**
     * A person goes missing, but only if nobody is looking. If their room is on the operator's
     * screen when it comes due, it waits and tries again. Watching someone protects them.
     */
    static final class Vanishing implements Perturbation {
        @Override
        public String name() {
            return "vanishing";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.IDENTITY;
        }

        @Override
        public int minLevel() {
            return 3;
        }

        @Override
        public double weight(WorldView w) {
            return candidates(w).isEmpty() ? 0 : 0.4;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            List<Entity> alone = candidates(ctx.world());
            if (alone.isEmpty()) {
                return false;
            }
            Entity victim = rng.weighted(alone, e -> e.forgotten() ? 5 : 1);
            vanish(ctx, victim, "not found at roll call");
            return true;
        }

        @Override
        public void resume(TickContext ctx, ScheduledPerturbation step, Rng rng) {
            WorldView w = ctx.world();
            Entity victim = w.entity(step.targets().getFirst());
            if (victim == null || !victim.present()) {
                return;
            }
            if (w.roomOf(victim) != null && w.roomOf(victim).equals(w.observedRoom())) {
                Director.schedule(ctx, this, 100 + rng.nextInt(100), step.targets(), null, "deferred: observed");
                return;
            }
            vanish(ctx, victim, "last badge ping " + w.roomOf(victim));
        }

        private void vanish(TickContext ctx, Entity victim, String reason) {
            Director.announce(ctx, this, List.of(victim.id()), ctx.world().roomOf(victim), victim.name());
            ctx.emit(new EntityStateChanged(victim.id(), victim.state(), EntityState.MISSING, reason));
        }

        private static List<Entity> candidates(WorldView w) {
            String observed = w.observedRoom();
            return Director.presentPersons(w).stream()
                    .filter(e -> {
                        String room = w.roomOf(e);
                        return room != null && !room.equals(observed) && Perception.personsIn(w, room) == 1;
                    })
                    .toList();
        }
    }
}
