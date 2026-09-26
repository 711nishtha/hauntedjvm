package hauntedjvm.core.event;

import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.world.Cell;
import java.util.List;

/** Doors, lights, cameras and objects: the physical facility. */
public sealed interface EnvironmentEvent extends SimEvent {

    /** @param by the entity that opened it, or {@code null} if nothing did */
    record DoorOpened(EntityId door, EntityId by) implements EnvironmentEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(door, by);
        }
    }

    record DoorClosed(EntityId door) implements EnvironmentEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(door);
        }
    }

    /** @param actor free-form origin, e.g. {@code OPERATOR} */
    record DoorLockChanged(EntityId door, boolean locked, String actor) implements EnvironmentEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(door);
        }
    }

    record DoorUnsealed(EntityId door) implements EnvironmentEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(door);
        }
    }

    /** @param until tick at which the mode reverts, or -1 for permanent */
    record LightChanged(EntityId room, LightMode mode, long until, String cause) implements EnvironmentEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(room);
        }
    }

    record CameraStatusChanged(EntityId camera, CameraStatus status, int timelineOffset, long until)
            implements EnvironmentEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(camera);
        }
    }

    record ObjectDisplaced(EntityId object, Cell from, Cell to) implements EnvironmentEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(object);
        }
    }

    record RoomRevealed(EntityId room) implements EnvironmentEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(room);
        }
    }
}
