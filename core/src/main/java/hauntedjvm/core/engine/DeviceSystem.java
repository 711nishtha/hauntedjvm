package hauntedjvm.core.engine;

import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.event.EnvironmentEvent.CameraStatusChanged;
import hauntedjvm.core.event.EnvironmentEvent.DoorClosed;
import hauntedjvm.core.event.EnvironmentEvent.LightChanged;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Cell;

/**
 * Doors swing shut, temporary light and camera conditions expire, and the facility reacts to its
 * controller processes: kill {@code lumen-ctl} and the building drops to emergency lighting; kill
 * {@code cam-mux} and every feed fills with interference.
 */
public final class DeviceSystem implements SimulationSystem {

    @Override
    public String name() {
        return "devices";
    }

    @Override
    public void tick(TickContext ctx) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        for (Entity e : w.ofKind(EntityKind.DEVICE)) {
            if (e.facet() instanceof DoorFacet d && d.open() && d.closeAt() >= 0 && d.closeAt() <= t
                    && !occupied(w, e.position())) {
                ctx.emit(new DoorClosed(e.id()));
            }
        }
        boolean lighting = w.lightingOnline();
        for (Entity room : w.ofKind(EntityKind.ROOM)) {
            if (!(room.facet() instanceof RoomFacet rf)) {
                continue;
            }
            if (!lighting) {
                if (rf.light() != LightMode.EMERGENCY && rf.light() != LightMode.OFF) {
                    ctx.emit(new LightChanged(room.id(), LightMode.EMERGENCY, -1, "lumen-ctl down"));
                }
            } else if (rf.light() == LightMode.EMERGENCY && rf.lightUntil() < 0) {
                ctx.emit(new LightChanged(room.id(), rf.normal(), -1, "lumen-ctl restored"));
            } else if (rf.lightUntil() >= 0 && rf.lightUntil() <= t) {
                ctx.emit(new LightChanged(room.id(), rf.normal(), -1, "restored"));
            }
        }
        boolean cameras = w.camerasOnline();
        for (Entity camera : w.ofKind(EntityKind.CAMERA)) {
            if (!(camera.facet() instanceof CameraFacet cf)) {
                continue;
            }
            if (!cameras) {
                if (cf.status() == CameraStatus.ONLINE) {
                    ctx.emit(new CameraStatusChanged(camera.id(), CameraStatus.INTERFERENCE, 0, -1));
                }
            } else if (cf.statusUntil() >= 0 && cf.statusUntil() <= t) {
                ctx.emit(new CameraStatusChanged(camera.id(), CameraStatus.ONLINE, 0, -1));
            } else if (cf.status() == CameraStatus.INTERFERENCE && cf.statusUntil() < 0) {
                ctx.emit(new CameraStatusChanged(camera.id(), CameraStatus.ONLINE, 0, -1));
            }
        }
    }

    private static boolean occupied(WorldView w, Cell doorway) {
        for (EntityKind kind : new EntityKind[] {EntityKind.PERSON, EntityKind.UNKNOWN}) {
            for (Entity e : w.ofKind(kind)) {
                if (doorway.equals(e.position())) {
                    return true;
                }
            }
        }
        return false;
    }
}
