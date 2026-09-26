package hauntedjvm.core.event;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.Goal;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.world.Cell;
import java.util.List;
import java.util.Objects;

/** Things that happen to or inside an entity. */
public sealed interface EntityEvent extends SimEvent {

    /** @param origin where the record came from: {@code ROSTER}, {@code SYSTEM}, or something stranger */
    record EntityCreated(Entity entity, String origin) implements EntityEvent {
        public EntityCreated {
            Objects.requireNonNull(entity);
        }

        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(entity.id());
        }
    }

    /** @param from {@code null} when an entity appears out of nowhere */
    record EntityMoved(EntityId id, Cell from, Cell to) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }
    }

    record EntityStateChanged(EntityId id, EntityState from, EntityState to, String reason) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }
    }

    record GoalChanged(EntityId id, Goal goal) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return goal == null ? SimEvent.of(id) : SimEvent.of(id, goal.target());
        }
    }

    /** Deltas to fear, awareness and corruption. Values are added, then clamped to [0,1]. */
    record AffectChanged(EntityId id, double fear, double awareness, double corruption, String cause)
            implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }
    }

    record EntityObserved(EntityId observer, EntityId subject, String roomCode) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(observer, subject);
        }
    }

    /** No mind in the facility holds a memory of this entity any more. */
    record EntityForgotten(EntityId id) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }
    }

    /** Milestone marker: corruption crossed a threshold. The change itself is an {@link AffectChanged}. */
    record EntityCorrupted(EntityId id, double level) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }
    }

    record IdentityClaimed(EntityId id, EntityId claimed) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id, claimed);
        }
    }

    /** @param reported pinned tracker position, or {@code null} to resume honest tracking */
    record BadgeReported(EntityId id, Cell reported) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }
    }

    record TrackingChanged(EntityId id, boolean tracked) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }
    }

    record RelationshipChanged(EntityId id, EntityId other, double trust, double familiarity) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id, other);
        }
    }

    /** The trace's id is ignored; the applier assigns the event's sequence number. */
    record MemoryFormed(EntityId id, MemoryTrace trace) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id, trace.subject());
        }
    }

    record MemoryFaded(EntityId id, long memoryId) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }
    }

    /** Anomaly-engine bookkeeping on an entity. */
    record MarkChanged(EntityId id, String mark, boolean present) implements EntityEvent {
        @Override
        public List<EntityId> subjects() {
            return SimEvent.of(id);
        }

        @Override
        public boolean internal() {
            return true;
        }
    }
}
