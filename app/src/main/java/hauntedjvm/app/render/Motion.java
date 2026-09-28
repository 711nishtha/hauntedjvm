package hauntedjvm.app.render;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Cell;
import java.util.HashMap;
import java.util.Map;

/**
 * Smooth movement between simulation ticks.
 *
 * <p>The simulation moves entities one cell per tick at most, a handful of times per second.
 * The renderer runs at display rate and interpolates each entity between its position in the
 * previous and current tick. Timing is decoupled completely: a slow tick rate looks slow, never
 * choppy, and a stalled simulation simply holds still.
 */
public final class Motion {

    private Map<EntityId, Cell> previous = new HashMap<>();
    private Map<EntityId, Cell> current = new HashMap<>();
    private Map<EntityId, Cell> previousReported = new HashMap<>();
    private Map<EntityId, Cell> currentReported = new HashMap<>();
    private long tick = Long.MIN_VALUE;

    /** Records a newly displayed tick. Consecutive ticks interpolate; anything else snaps. */
    public void observe(WorldView world) {
        long t = world.tick();
        if (t == tick) {
            return;
        }
        boolean consecutive = t == tick + 1;
        Map<EntityId, Cell> next = new HashMap<>();
        Map<EntityId, Cell> nextReported = new HashMap<>();
        for (Entity e : world.entities()) {
            if (e.position() != null && e.kind().mobile()) {
                next.put(e.id(), e.position());
            }
            if (e.reportedPosition() != null) {
                nextReported.put(e.id(), e.reportedPosition());
            }
        }
        previous = consecutive ? current : next;
        previousReported = consecutive ? currentReported : nextReported;
        current = next;
        currentReported = nextReported;
        tick = t;
    }

    public void reset() {
        tick = Long.MIN_VALUE;
    }

    /** True position in cell units (centre of cell = x + 0.5), interpolated. */
    public double[] position(EntityId id, Cell fallback, double alpha) {
        return lerp(previous.get(id), current.getOrDefault(id, fallback), alpha);
    }

    public double[] reported(EntityId id, Cell fallback, double alpha) {
        return lerp(previousReported.get(id), currentReported.getOrDefault(id, fallback), alpha);
    }

    /** Whether the entity changed cell in the latest tick; drives walk animation. */
    public boolean moving(EntityId id) {
        Cell a = previous.get(id);
        Cell b = current.get(id);
        return a != null && b != null && !a.equals(b);
    }

    private static double[] lerp(Cell from, Cell to, double alpha) {
        if (to == null) {
            return null;
        }
        if (from == null || from.manhattan(to) > 1) {
            return new double[] {to.x() + 0.5, to.y() + 0.5};
        }
        double a = Math.clamp(alpha, 0.0, 1.0);
        // Ease so steps read as walking, not sliding.
        double s = a * a * (3 - 2 * a);
        return new double[] {from.x() + 0.5 + (to.x() - from.x()) * s, from.y() + 0.5 + (to.y() - from.y()) * s};
    }
}
