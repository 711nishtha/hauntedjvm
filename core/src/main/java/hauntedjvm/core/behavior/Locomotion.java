package hauntedjvm.core.behavior;

import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.Goal;
import hauntedjvm.core.event.EntityEvent.EntityMoved;
import hauntedjvm.core.event.EnvironmentEvent.DoorOpened;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Cell;

/**
 * Moves minds one cell at a time along flow fields.
 *
 * <p>Speed is expressed as a pace: an entity may step on ticks where {@code (tick + serial) % pace
 * == 0}. Cadence is therefore a pure function of state and time, so it needs no per-entity timer
 * in the world state and no events to maintain one. The serial offset keeps a crowd from moving
 * in lockstep.
 */
public final class Locomotion {

    private Locomotion() {
    }

    public static int pace(EntityState state) {
        return switch (state) {
            case AFRAID, AVOIDING, FOLLOWING -> 1;
            case CORRUPTED, AWARE, DORMANT, MIMICKING -> 3;
            default -> 2;
        };
    }

    /** Whether the entity is frozen because the operator is looking at its room. */
    public static boolean heldByObservation(WorldView w, Entity e) {
        String room = w.roomOf(e);
        if (room == null || !room.equals(w.observedRoom())) {
            return false;
        }
        return e.hasMark(Marks.OBSERVER_SENSITIVE)
                || (e.state() == EntityState.AWARE && e.mind().goal() != null && e.position().equals(e.mind().goal().cell()));
    }

    /**
     * Takes one step toward the entity's goal if its pace allows.
     *
     * @return true if the entity moved
     */
    public static boolean advance(TickContext ctx, Entity e, int pace) {
        Goal goal = e.hasMind() ? e.mind().goal() : null;
        if (goal == null || e.position() == null || e.position().equals(goal.cell())) {
            return false;
        }
        if ((ctx.tick() + e.id().value()) % Math.max(1, pace) != 0) {
            return false;
        }
        WorldView w = ctx.world();
        Cell next = ctx.navigator().nextStep(e.position(), goal.cell(), w.blockedDoors());
        if (next == null || next.equals(e.position())) {
            return false;
        }
        return stepTo(ctx, e, next);
    }

    /** Steps onto an adjacent cell, opening a closed door on the way. */
    public static boolean stepTo(TickContext ctx, Entity e, Cell next) {
        WorldView w = ctx.world();
        if (!w.map().passable(next, w.blockedDoors())) {
            return false;
        }
        Entity door = w.doorAt(next);
        if (door != null && door.facet() instanceof DoorFacet d && !d.open()) {
            ctx.emit(new DoorOpened(door.id(), e.id()));
        }
        ctx.emit(new EntityMoved(e.id(), e.position(), next));
        return true;
    }

    /** One step that most reduces straight-line distance; for closing the last few metres on a moving target. */
    public static Cell greedyStep(WorldView w, Cell from, Cell target) {
        Cell best = null;
        int bestDistance = from.manhattan(target);
        for (Cell n : new Cell[] {from.offset(0, -1), from.offset(1, 0), from.offset(0, 1), from.offset(-1, 0)}) {
            if (w.map().passable(n, w.blockedDoors()) && n.manhattan(target) < bestDistance) {
                best = n;
                bestDistance = n.manhattan(target);
            }
        }
        return best;
    }
}
