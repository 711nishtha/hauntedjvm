package hauntedjvm.core.state;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.incident.IncidentState;
import hauntedjvm.core.world.Cell;
import hauntedjvm.core.world.FacilityMap;
import java.util.List;

/**
 * Read-only view of the world at one tick. Systems, detectors, the renderer and the inspector
 * all work against this interface; only the event applier sees the mutable implementation.
 */
public interface WorldView {

    FacilityMap map();

    long tick();

    /** Sequence number of the last event reflected in this view, or -1 before genesis. */
    long lastSeq();

    Entity entity(EntityId id);

    /** Every entity, ordered by serial. */
    List<Entity> entities();

    /** Entities of one kind, ordered by serial. */
    List<Entity> ofKind(EntityKind kind);

    /** Present persons and unaccounted entities inside a room, ordered by serial. */
    List<Entity> occupants(String roomCode);

    Entity room(String roomCode);

    Entity camera(String cameraCode);

    Entity doorAt(Cell cell);

    /** A facility process by command name, or {@code null}. */
    Entity process(String command);

    /** The facility controller entity. */
    Entity system();

    LightMode light(String roomCode);

    /** Navigation mask of doors that are locked or sealed. */
    long blockedDoors();

    IncidentState incident();

    /** Room code of an entity's true position, or {@code null}. */
    default String roomOf(Entity e) {
        return e.position() == null ? null : map().roomCodeAt(e.position());
    }

    /** The room the operator is currently watching, or {@code null}. */
    default String observedRoom() {
        String cam = incident().observedCamera();
        if (cam == null) {
            return null;
        }
        Entity camera = camera(cam);
        return camera == null ? null : map().roomCodeAt(camera.position());
    }

    /** Whether the badge tracker is running; while it is down, reported positions freeze. */
    default boolean trackerOnline() {
        return processRunning("badge-trackd");
    }

    /** Whether the camera multiplexer is running; while it is down, every feed degrades. */
    default boolean camerasOnline() {
        return processRunning("cam-mux");
    }

    /** Whether the lighting controller is running; while it is down, rooms fall back to emergency light. */
    default boolean lightingOnline() {
        return processRunning("lumen-ctl");
    }

    private boolean processRunning(String command) {
        Entity p = process(command);
        return p == null || p.state() == hauntedjvm.core.entity.EntityState.RUNNING;
    }
}
