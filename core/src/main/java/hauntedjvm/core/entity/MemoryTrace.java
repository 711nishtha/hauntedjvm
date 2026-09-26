package hauntedjvm.core.entity;

import hauntedjvm.core.world.Cell;

/**
 * One remembered observation.
 *
 * <p>{@code sourceSeq} points at the logged event that produced the memory. It is never
 * trusted blindly: the anomaly detector follows it back into the event log, and a memory whose
 * source does not exist, or does not say what the memory claims, is how "remembering something
 * that never happened" becomes observable rather than a line of flavour text.
 *
 * @param id        unique per run; equal to the sequence number of the forming event
 * @param tick      when the memory was formed (may be in the future for carried-over memories)
 * @param subject   the entity remembered, if any
 * @param roomCode  where it happened
 * @param cell      precise location, for object memories
 * @param strength  initial vividness in {@code [0,1]}; fades with the owner's half-life
 * @param valence   emotional colour from -1 (terror) to +1 (comfort)
 * @param sourceSeq sequence number of the causing event, or -1 if none
 * @param note      short human-readable gist shown in the inspector
 */
public record MemoryTrace(
        long id,
        long tick,
        MemoryKind kind,
        EntityId subject,
        String roomCode,
        Cell cell,
        double strength,
        double valence,
        long sourceSeq,
        String note) {

    public MemoryTrace {
        strength = Math.clamp(strength, 0.0, 1.0);
        valence = Math.clamp(valence, -1.0, 1.0);
    }

    public double strengthAt(long now, double halfLife) {
        long age = Math.max(0, now - tick);
        return strength * Math.pow(0.5, age / halfLife);
    }

    public MemoryTrace withId(long newId) {
        return new MemoryTrace(newId, tick, kind, subject, roomCode, cell, strength, valence, sourceSeq, note);
    }

    public MemoryTrace withTick(long newTick) {
        return new MemoryTrace(id, newTick, kind, subject, roomCode, cell, strength, valence, sourceSeq, note);
    }

    public MemoryTrace withKind(MemoryKind newKind) {
        return new MemoryTrace(id, tick, newKind, subject, roomCode, cell, strength, valence, sourceSeq, note);
    }
}
