package hauntedjvm.core.anomaly;

import hauntedjvm.core.behavior.Perception;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.event.EnvironmentEvent.CameraStatusChanged;
import hauntedjvm.core.event.EnvironmentEvent.DoorOpened;
import hauntedjvm.core.event.EnvironmentEvent.LightChanged;
import hauntedjvm.core.event.EnvironmentEvent.ObjectDisplaced;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.DoorLayout;
import hauntedjvm.core.world.RoomLayout;
import java.util.ArrayList;
import java.util.List;

/** Doors, lights, cameras and objects doing things nothing made them do. */
final class EnvironmentalPerturbations {

    private EnvironmentalPerturbations() {
    }

    /** A door opens with nobody near it. More likely on the feed being watched. */
    static final class DoorUnattended implements Perturbation {
        @Override
        public String name() {
            return "door-unattended";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.VISUAL;
        }

        @Override
        public int minLevel() {
            return 0;
        }

        @Override
        public double weight(WorldView w) {
            return 1.0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            String observed = w.observedRoom();
            List<Entity> candidates = new ArrayList<>();
            for (Entity e : w.ofKind(EntityKind.DEVICE)) {
                if (e.facet() instanceof DoorFacet d && !d.open() && !d.blocked()) {
                    candidates.add(e);
                }
            }
            Entity door = rng.weighted(candidates, e -> {
                DoorLayout layout = w.map().doorAt(e.position());
                return layout.connects(String.valueOf(observed)) ? 4.0 : 1.0;
            });
            if (door == null) {
                return false;
            }
            DoorLayout layout = w.map().doorAt(door.position());
            EventRecord cause = Director.announce(ctx, this, List.of(door.id()), layout.roomA(), layout.code());
            ctx.emit(new DoorOpened(door.id(), null));
            Director.witnesses(ctx, layout.roomA(), cause.seq(), 0.3, layout.code() + " opened by itself");
            Director.witnesses(ctx, layout.roomB(), cause.seq(), 0.3, layout.code() + " opened by itself");
            return true;
        }
    }

    /** Lights stutter in an occupied room. */
    static final class LightFlicker implements Perturbation {
        @Override
        public String name() {
            return "light-flicker";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.VISUAL;
        }

        @Override
        public int minLevel() {
            return 0;
        }

        @Override
        public double weight(WorldView w) {
            return w.lightingOnline() ? 1.2 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            List<String> rooms = Director.listedRooms(w);
            String room = rng.weighted(rooms, r -> 1 + 2 * Perception.personsIn(w, r)
                    + (Director.unknownPresent(w) && w.occupants(r).stream()
                        .anyMatch(e -> e.kind() == EntityKind.UNKNOWN) ? 6 : 0));
            if (room == null || w.light(room) == LightMode.OFF) {
                return false;
            }
            EventRecord cause = Director.announce(ctx, this, List.of(w.room(room).id()), room, "flicker");
            ctx.emit(new LightChanged(w.room(room).id(), LightMode.FLICKER, ctx.tick() + 20 + rng.nextInt(50),
                    "no fault logged"));
            Director.witnesses(ctx, room, cause.seq(), 0.2, "the lights in " + room + " stuttered");
            return true;
        }
    }

    /** Static on a feed. Only the operator notices. */
    static final class CameraInterference implements Perturbation {
        @Override
        public String name() {
            return "camera-interference";
        }

        @Override
        public AnomalyCategory category() {
            return AnomalyCategory.VISUAL;
        }

        @Override
        public int minLevel() {
            return 0;
        }

        @Override
        public double weight(WorldView w) {
            return 0.9;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            Entity camera = pickCamera(w, rng, 0.5);
            if (camera == null || ((CameraFacet) camera.facet()).status() != CameraStatus.ONLINE) {
                return false;
            }
            Director.announce(ctx, this, List.of(camera.id()), ((CameraFacet) camera.facet()).roomCode(), "static");
            ctx.emit(new CameraStatusChanged(camera.id(), CameraStatus.INTERFERENCE, 0, ctx.tick() + 10 + rng.nextInt(35)));
            return true;
        }
    }

    /**
     * A feed starts showing the room as it was some minutes ago. The renderer reconstructs that
     * past from the timeline; people who were there then appear there now.
     */
    static final class CameraLoop implements Perturbation {
        @Override
        public String name() {
            return "camera-loop";
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
            return w.tick() > 600 ? 0.8 : 0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            Entity camera = pickCamera(w, rng, 0.35);
            if (camera == null || ((CameraFacet) camera.facet()).status() != CameraStatus.ONLINE) {
                return false;
            }
            int offset = (int) Math.min(ctx.tick() - 1, 90 + rng.nextInt(420));
            Director.announce(ctx, this, List.of(camera.id()), ((CameraFacet) camera.facet()).roomCode(),
                    "offset " + offset);
            ctx.emit(new CameraStatusChanged(camera.id(), CameraStatus.LOOPING, offset,
                    ctx.tick() + 60 + rng.nextInt(160)));
            return true;
        }
    }

    /** Something moves an object, but only where nobody could see it happen. */
    static final class ObjectDisplacement implements Perturbation {
        @Override
        public String name() {
            return "object-displaced";
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
            return Director.unwitnessedRooms(w).isEmpty() ? 0 : 1.0;
        }

        @Override
        public boolean apply(TickContext ctx, Rng rng) {
            WorldView w = ctx.world();
            List<Entity> movable = new ArrayList<>();
            List<String> quiet = Director.unwitnessedRooms(w);
            for (Entity e : w.ofKind(EntityKind.OBJECT)) {
                if (e.position() != null && quiet.contains(w.roomOf(e))) {
                    movable.add(e);
                }
            }
            if (movable.isEmpty()) {
                return false;
            }
            Entity object = rng.pick(movable);
            RoomLayout target = w.map().room(rng.pick(quiet));
            Cell to = rng.pick(target.cells());
            if (target.terminals().contains(to) || to.equals(object.position())) {
                return false;
            }
            Director.announce(ctx, this, List.of(object.id()), target.code(), object.name());
            ctx.emit(new ObjectDisplaced(object.id(), object.position(), to));
            return true;
        }
    }

    private static Entity pickCamera(WorldView w, Rng rng, double preferObserved) {
        List<Entity> cams = w.ofKind(EntityKind.CAMERA);
        if (cams.isEmpty()) {
            return null;
        }
        String observed = w.incident().observedCamera();
        if (observed != null && w.camera(observed) != null && rng.chance(preferObserved)) {
            return w.camera(observed);
        }
        return rng.pick(cams);
    }
}
