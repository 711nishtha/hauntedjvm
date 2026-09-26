package hauntedjvm.core.entity;

import hauntedjvm.core.world.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of one entity.
 *
 * <p>The world state holds entities in a mutable map but the entities themselves never change;
 * applying an event swaps in a new record. That single decision gives the rest of the system a
 * lot for free: snapshots are shallow map copies that share unchanged entities, the renderer
 * can hold a frame without locks, and a reconstructed past can never alias the live present.
 *
 * @param position         true location; {@code null} for non-spatial entities and the missing
 * @param reportedPosition where the badge tracker says the entity is; may disagree with {@code position}
 * @param tracked          whether the entity carries a tracker badge at all
 * @param badgeOverride    true while the reported position is pinned by an anomaly
 * @param identity         who the entity claims to be; normally its own id
 * @param forgotten        true once no other mind holds a memory of this entity
 * @param marks            sorted labels the anomaly engine uses for its own bookkeeping
 */
public record Entity(
        EntityId id,
        UUID uuid,
        EntityKind kind,
        String name,
        Cell position,
        Cell reportedPosition,
        boolean tracked,
        boolean badgeOverride,
        EntityState state,
        EntityId identity,
        double corruption,
        boolean forgotten,
        long createdTick,
        List<String> marks,
        Facet facet) {

    public Entity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(facet, "facet");
        identity = identity == null ? id : identity;
        corruption = Math.clamp(corruption, 0.0, 1.0);
        marks = List.copyOf(marks);
    }

    public boolean hasMark(String mark) {
        return marks.contains(mark);
    }

    public boolean present() {
        return position != null && state != EntityState.MISSING;
    }

    public boolean impersonating() {
        return !identity.equals(id);
    }

    public Mind mind() {
        if (facet instanceof Mind m) {
            return m;
        }
        throw new IllegalStateException(id + " (" + kind + ") has no mind");
    }

    public boolean hasMind() {
        return facet instanceof Mind;
    }

    public Entity withPosition(Cell newPosition, Cell newReported) {
        return new Entity(id, uuid, kind, name, newPosition, newReported, tracked, badgeOverride, state, identity,
                corruption, forgotten, createdTick, marks, facet);
    }

    public Entity withBadge(Cell reported, boolean override) {
        return new Entity(id, uuid, kind, name, position, reported, tracked, override, state, identity, corruption,
                forgotten, createdTick, marks, facet);
    }

    public Entity withTracked(boolean isTracked) {
        return new Entity(id, uuid, kind, name, position, reportedPosition, isTracked, badgeOverride, state, identity,
                corruption, forgotten, createdTick, marks, facet);
    }

    public Entity withState(EntityState newState) {
        return new Entity(id, uuid, kind, name, position, reportedPosition, tracked, badgeOverride, newState, identity,
                corruption, forgotten, createdTick, marks, facet);
    }

    public Entity withIdentity(EntityId claimed) {
        return new Entity(id, uuid, kind, name, position, reportedPosition, tracked, badgeOverride, state, claimed,
                corruption, forgotten, createdTick, marks, facet);
    }

    public Entity withCorruption(double value) {
        return new Entity(id, uuid, kind, name, position, reportedPosition, tracked, badgeOverride, state, identity,
                value, forgotten, createdTick, marks, facet);
    }

    public Entity withForgotten(boolean value) {
        return new Entity(id, uuid, kind, name, position, reportedPosition, tracked, badgeOverride, state, identity,
                corruption, value, createdTick, marks, facet);
    }

    public Entity withMark(String mark, boolean present) {
        List<String> next = new ArrayList<>(marks);
        next.remove(mark);
        if (present) {
            next.add(mark);
            next.sort(null);
        }
        return new Entity(id, uuid, kind, name, position, reportedPosition, tracked, badgeOverride, state, identity,
                corruption, forgotten, createdTick, next, facet);
    }

    public Entity withFacet(Facet newFacet) {
        return new Entity(id, uuid, kind, name, position, reportedPosition, tracked, badgeOverride, state, identity,
                corruption, forgotten, createdTick, marks, newFacet);
    }
}
