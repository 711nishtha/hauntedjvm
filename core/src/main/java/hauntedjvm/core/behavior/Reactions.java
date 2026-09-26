package hauntedjvm.core.behavior;

import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Traits;
import hauntedjvm.core.event.EntityEvent.AffectChanged;
import hauntedjvm.core.event.EntityEvent.EntityCorrupted;
import hauntedjvm.core.event.EntityEvent.MemoryFormed;
import hauntedjvm.core.world.Cell;

/**
 * How minds absorb experiences. Shared by perception, conversation and the anomaly engine so a
 * door opening by itself frightens someone the same way no matter which system noticed it.
 */
public final class Reactions {

    /** Corruption at which a mind is flagged; also the threshold for the milestone event. */
    public static final double CORRUPTION_MILESTONE = 0.5;

    private Reactions() {
    }

    /**
     * A person witnessed something wrong.
     *
     * @param sourceSeq the event that caused it; the memory points back there
     * @param intensity 0..1, how frightening
     */
    public static void witnessAnomaly(TickContext ctx, Entity person, long sourceSeq, String roomCode,
                                      double intensity, String note) {
        if (!person.present() || person.kind() != EntityKind.PERSON) {
            return;
        }
        Traits traits = person.mind().traits();
        remember(ctx, person.id(), new MemoryTrace(0, ctx.tick(), MemoryKind.SAW_ANOMALY, null, roomCode, null,
                0.55 + 0.4 * intensity, -(0.4 + 0.5 * intensity), sourceSeq, note));
        feel(ctx, person, intensity * 0.35 * (0.5 + traits.fear()), 0.05 * intensity * (0.5 + traits.awareness()), 0,
                note);
    }

    public static void remember(TickContext ctx, EntityId owner, MemoryTrace trace) {
        ctx.emit(new MemoryFormed(owner, trace));
    }

    /** Remembers an entity seen at a place, with the given colour and vividness. */
    public static void rememberSeeing(TickContext ctx, Entity owner, EntityId subject, String roomCode, Cell cell,
                                      MemoryKind kind, double strength, double valence, long sourceSeq, String note) {
        remember(ctx, owner.id(), new MemoryTrace(0, ctx.tick(), kind, subject, roomCode, cell, strength, valence,
                sourceSeq, note));
    }

    /** Applies affect deltas and emits the corruption milestone when it is crossed. */
    public static void feel(TickContext ctx, Entity entity, double fear, double awareness, double corruption,
                            String cause) {
        if (Math.abs(fear) < 1e-4 && Math.abs(awareness) < 1e-4 && Math.abs(corruption) < 1e-4) {
            return;
        }
        double before = entity.corruption();
        ctx.emit(new AffectChanged(entity.id(), fear, awareness, corruption, cause));
        double after = ctx.world().entity(entity.id()).corruption();
        if (before < CORRUPTION_MILESTONE && after >= CORRUPTION_MILESTONE) {
            ctx.emit(new EntityCorrupted(entity.id(), after));
        }
    }
}
