package hauntedjvm.core.memory;

import hauntedjvm.core.engine.SimulationSystem;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.event.EntityEvent.EntityForgotten;
import hauntedjvm.core.event.EntityEvent.MemoryFaded;
import hauntedjvm.core.state.WorldView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fading and collective forgetting.
 *
 * <p>Memory strength decays lazily (see {@link MemoryTrace#strengthAt}), so this system only has
 * to notice when a memory has become too faint to matter and drop it. Once no present mind
 * holds any memory of a person, that person is marked forgotten, and forgotten people are the
 * ones the unaccounted entity can replace. Nothing scripts that; it falls out of who talks to whom.
 */
public final class MemorySystem implements SimulationSystem {

    /** Below this effective strength a memory is dropped. */
    public static final double FADE_THRESHOLD = 0.05;
    /** A memory must be at least this vivid to count as remembering someone. */
    public static final double REMEMBER_THRESHOLD = 0.08;
    private static final int FADE_PERIOD = 20;
    private static final int FORGET_PERIOD = 50;
    private static final int MAX_FADES_PER_PASS = 4;
    /** Nobody can be forgotten before the shift has had time to meet each other. */
    private static final long FORGET_GRACE = 900;

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void tick(TickContext ctx) {
        WorldView w = ctx.world();
        long t = ctx.tick();
        for (EntityKind kind : List.of(EntityKind.PERSON, EntityKind.UNKNOWN)) {
            for (Entity e : w.ofKind(kind)) {
                if ((t + e.id().value()) % FADE_PERIOD == 0) {
                    fade(ctx, e);
                }
            }
        }
        if (t % FORGET_PERIOD == 0 && t >= FORGET_GRACE) {
            forget(ctx);
        }
    }

    private void fade(TickContext ctx, Entity e) {
        Mind mind = e.mind();
        double halfLife = mind.traits().memoryHalfLife();
        int faded = 0;
        for (MemoryTrace m : mind.memories()) {
            if (faded >= MAX_FADES_PER_PASS) {
                break;
            }
            if (m.strengthAt(ctx.tick(), halfLife) < FADE_THRESHOLD) {
                ctx.emit(new MemoryFaded(e.id(), m.id()));
                faded++;
            }
        }
    }

    private void forget(TickContext ctx) {
        WorldView w = ctx.world();
        List<Entity> persons = w.ofKind(EntityKind.PERSON);
        Set<EntityId> remembered = new HashSet<>();
        long t = ctx.tick();
        for (Entity p : persons) {
            if (!p.present()) {
                continue;
            }
            double halfLife = p.mind().traits().memoryHalfLife();
            for (MemoryTrace m : p.mind().memories()) {
                if (m.subject() != null && !m.subject().equals(p.id())
                        && m.strengthAt(t, halfLife) >= REMEMBER_THRESHOLD) {
                    remembered.add(m.subject());
                }
            }
        }
        for (Entity p : persons) {
            if (!p.forgotten() && !remembered.contains(p.id())) {
                ctx.emit(new EntityForgotten(p.id()));
            }
        }
    }
}
