package hauntedjvm.core.anomaly;

import hauntedjvm.core.behavior.Reactions;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.StateMachine;
import hauntedjvm.core.event.CommunicationEvent.Channel;
import hauntedjvm.core.event.CommunicationEvent.MessageSent;
import hauntedjvm.core.event.EntityEvent.BadgeReported;
import hauntedjvm.core.event.EntityEvent.EntityCreated;
import hauntedjvm.core.event.EntityEvent.EntityMoved;
import hauntedjvm.core.event.EntityEvent.EntityStateChanged;
import hauntedjvm.core.event.EntityEvent.MarkChanged;
import hauntedjvm.core.event.EnvironmentEvent.DoorUnsealed;
import hauntedjvm.core.event.EnvironmentEvent.RoomRevealed;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.CameraMount;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.DoorLayout;
import java.util.List;
import java.util.UUID;

/**
 * Things that should almost never happen. Each can happen at most once per run, is gated on
 * escalation, and fires with a tiny seeded probability. Deliberately undocumented in the README.
 */
final class RareEvents {

    private RareEvents() {
    }

    static List<Perturbation> all() {
        return List.of(new VoidDoor(), new DoubleOutcome(), new ShyRecord(), new Return(), new FutureNotice());
    }

    static boolean happened(WorldView w, Perturbation p) {
        Entity system = w.system();
        return system != null && system.hasMark(Marks.RARE_PREFIX + p.name());
    }

    static void markHappened(TickContext ctx, Perturbation p) {
        ctx.emit(new MarkChanged(ctx.world().system().id(), Marks.RARE_PREFIX + p.name(), true));
    }

    /** The sealed door opens onto a room the registry never listed, and it has a camera. */
    static final class VoidDoor implements Perturbation {
        @Override
        public String name() {
            return "void-door";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.SPATIAL;
        }

        @Override
        public int minLevel() {
            return 3;
        }

        @Override
        public double weight(WorldView w) {
            return w.map().doors().stream().anyMatch(DoorLayout::sealed) ? 1 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            DoorLayout layout = w.map().doors().stream().filter(DoorLayout::sealed).findFirst().orElse(null);
            Entity door = layout == null ? null : w.doorAt(layout.cell());
            if (door == null || !(door.facet() instanceof DoorFacet df) || !df.sealed()) {
                return false;
            }
            String hidden = w.map().room(layout.roomA()).listed() ? layout.roomB() : layout.roomA();
            Director.announce(ctx, this, List.of(door.id()), hidden, "unsealed");
            ctx.emit(new DoorUnsealed(door.id()));
            ctx.emit(new RoomRevealed(w.room(hidden).id()));
            for (CameraMount m : w.map().cameras()) {
                if (m.hidden() && m.roomCode().equals(hidden) && w.camera(m.code()) == null) {
                    EntityId id = EntityId.of(w.entities().stream().mapToInt(e -> e.id().value()).max().orElse(0) + 1);
                    Entity cam = new Entity(id, new UUID(rng.nextLong(), rng.nextLong()), EntityKind.CAMERA, m.code(),
                            m.mount(), null, false, false, EntityState.ONLINE, id, 0, false, ctx.tick(), List.of(),
                            new CameraFacet(m.code(), hidden, m.mount(), CameraStatus.ONLINE, 0, -1));
                    ctx.emitDated(new EntityCreated(cam, IdentityPerturbations.ORIGIN_NONE), 0);
                }
            }
            return true;
        }
    }

    /** The same moment recorded twice, with two different outcomes. Both records stand. */
    static final class DoubleOutcome implements Perturbation {
        @Override
        public String name() {
            return "double-outcome";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.TEMPORAL;
        }

        @Override
        public int minLevel() {
            return 2;
        }

        @Override
        public double weight(WorldView w) {
            return Director.presentPersons(w).stream().anyMatch(e -> e.state() == EntityState.NORMAL) ? 1 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            List<Entity> calm = Director.presentPersons(ctx.world()).stream()
                    .filter(e -> e.state() == EntityState.NORMAL).toList();
            if (calm.isEmpty()) {
                return false;
            }
            Entity p = rng.pick(calm);
            if (!StateMachine.canTransition(EntityKind.PERSON, EntityState.NORMAL, EntityState.AFRAID)) {
                return false;
            }
            Director.announce(ctx, this, List.of(p.id()), ctx.world().roomOf(p), p.name());
            ctx.emit(new EntityStateChanged(p.id(), EntityState.NORMAL, EntityState.AFRAID, "heard their name"));
            ctx.emit(new EntityStateChanged(p.id(), EntityState.NORMAL, EntityState.SUSPICIOUS, "heard nothing"));
            return true;
        }
    }

    /** An anomaly record that will withdraw itself the moment the operator looks at it. */
    static final class ShyRecord implements Perturbation {
        @Override
        public String name() {
            return "shy-record";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.OBSERVATION;
        }

        @Override
        public int minLevel() {
            return 1;
        }

        @Override
        public double weight(WorldView w) {
            return w.incident().active().isEmpty() ? 0 : 1;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            List<AnomalyRecord> active = ctx.world().incident().active();
            if (active.isEmpty()) {
                return false;
            }
            AnomalyRecord target = rng.pick(active);
            Director.announce(ctx, this, List.of(), target.anomaly().roomCode(), target.anomaly().id());
            ctx.emit(new MarkChanged(ctx.world().system().id(), Marks.SHY_PREFIX + target.anomaly().id(), true));
            return true;
        }
    }

    /** Someone who went missing comes back. They are aware now, and they remember a room. */
    static final class Return implements Perturbation {
        @Override
        public String name() {
            return "return";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.IDENTITY;
        }

        @Override
        public int minLevel() {
            return 4;
        }

        @Override
        public double weight(WorldView w) {
            return w.ofKind(EntityKind.PERSON).stream().anyMatch(e -> e.state() == EntityState.MISSING) ? 1 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            List<Entity> missing = w.ofKind(EntityKind.PERSON).stream()
                    .filter(e -> e.state() == EntityState.MISSING).toList();
            DoorLayout sealed = w.map().doors().stream().filter(DoorLayout::sealed).findFirst().orElse(null);
            if (missing.isEmpty() || sealed == null) {
                return false;
            }
            Entity p = rng.pick(missing);
            Cell at = sealed.cell().offset(0, 1);
            EventRecord cause = Director.announce(ctx, this, List.of(p.id()), w.map().roomCodeAt(at), p.name());
            ctx.emit(new EntityMoved(p.id(), null, at));
            ctx.emit(new EntityStateChanged(p.id(), EntityState.MISSING, EntityState.AWARE, "returned"));
            ctx.emit(new BadgeReported(p.id(), null));
            String hidden = w.map().room(sealed.roomA()).listed() ? sealed.roomB() : sealed.roomA();
            Reactions.remember(ctx, p.id(), new MemoryTrace(0, ctx.tick(), MemoryKind.SAW_ANOMALY, null, hidden, null,
                    1.0, -1.0, cause.seq(), "was in the room that is not on the plan"));
            return true;
        }
    }

    /** The incident log reports a disappearance that has not happened yet. */
    static final class FutureNotice implements Perturbation {
        @Override
        public String name() {
            return "future-notice";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.TEMPORAL;
        }

        @Override
        public int minLevel() {
            return 2;
        }

        @Override
        public double weight(WorldView w) {
            return Director.presentPersons(w).isEmpty() ? 0 : 1;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            Entity p = rng.pick(Director.presentPersons(w));
            long due = 300 + rng.nextInt(300);
            Entity log = w.process("incident-log");
            Director.announce(ctx, this, List.of(p.id()), null, p.name() + " in " + due);
            ctx.emitDated(new MessageSent(log == null ? null : log.id(), null, Channel.INCIDENT_LOG,
                    p.name() + " REPORTED MISSING.", null), ctx.tick() + due);
            Director.schedule(ctx, IdentityPerturbations.VANISHING, due, List.of(p.id()), null, "foretold");
            return true;
        }
    }
}
