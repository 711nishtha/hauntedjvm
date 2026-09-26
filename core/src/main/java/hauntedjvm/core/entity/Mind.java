package hauntedjvm.core.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The inner life of a person or of the unaccounted entity.
 *
 * @param role          night-shift job; {@code null} for the unaccounted
 * @param memories      bounded, oldest-first
 * @param relationships sorted by the other entity's serial, so iteration is deterministic
 */
public record Mind(
        Role role,
        Traits traits,
        Decaying fear,
        Decaying awareness,
        List<MemoryTrace> memories,
        List<Relationship> relationships,
        Goal goal) implements Facet {

    /** Enough to remember a night shift's worth of salient things without unbounded growth. */
    public static final int MEMORY_CAPACITY = 32;

    public Mind {
        memories = List.copyOf(memories);
        relationships = List.copyOf(relationships);
    }

    public double fearAt(long tick) {
        return fear.at(tick);
    }

    public double awarenessAt(long tick) {
        return awareness.at(tick);
    }

    public Optional<Relationship> relationship(EntityId other) {
        for (Relationship r : relationships) {
            if (r.other().equals(other)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public Optional<MemoryTrace> memory(long id) {
        return memories.stream().filter(m -> m.id() == id).findFirst();
    }

    /**
     * How much this mind dreads a room right now: the strongest negative memory tied to it,
     * weighted by current vividness. Positive memories do not cancel fear.
     */
    public double dread(String roomCode, long tick) {
        double worst = 0;
        for (MemoryTrace m : memories) {
            if (m.valence() < 0 && roomCode.equals(m.roomCode())) {
                worst = Math.max(worst, -m.valence() * m.strengthAt(tick, traits.memoryHalfLife()));
            }
        }
        return worst;
    }

    /** The most vivid unsettling memory, if any is still vivid enough to act on. */
    public Optional<MemoryTrace> mostSalientFear(long tick, double threshold) {
        MemoryTrace best = null;
        double bestScore = threshold;
        for (MemoryTrace m : memories) {
            if (m.valence() >= 0 || m.roomCode() == null) {
                continue;
            }
            double score = -m.valence() * m.strengthAt(tick, traits.memoryHalfLife());
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return Optional.ofNullable(best);
    }

    public Mind withFear(Decaying newFear) {
        return new Mind(role, traits, newFear, awareness, memories, relationships, goal);
    }

    public Mind withAwareness(Decaying newAwareness) {
        return new Mind(role, traits, fear, newAwareness, memories, relationships, goal);
    }

    public Mind withGoal(Goal newGoal) {
        return new Mind(role, traits, fear, awareness, memories, relationships, newGoal);
    }

    /** Adds a memory, evicting the faintest one when full. Ties evict the oldest. */
    public Mind withMemory(MemoryTrace trace, long tick) {
        List<MemoryTrace> next = new ArrayList<>(memories);
        next.add(trace);
        if (next.size() > MEMORY_CAPACITY) {
            double halfLife = traits.memoryHalfLife();
            MemoryTrace faintest = next.stream()
                    .min(Comparator.<MemoryTrace>comparingDouble(m -> m.strengthAt(tick, halfLife))
                            .thenComparingLong(MemoryTrace::id))
                    .orElseThrow();
            next.remove(faintest);
        }
        return new Mind(role, traits, fear, awareness, next, relationships, goal);
    }

    public Mind withoutMemory(long memoryId) {
        List<MemoryTrace> next = new ArrayList<>(memories);
        next.removeIf(m -> m.id() == memoryId);
        return new Mind(role, traits, fear, awareness, next, relationships, goal);
    }

    public Mind withRelationship(Relationship updated) {
        List<Relationship> next = new ArrayList<>(relationships);
        next.removeIf(r -> r.other().equals(updated.other()));
        next.add(updated);
        next.sort(Comparator.comparing(Relationship::other));
        return new Mind(role, traits, fear, awareness, memories, next, goal);
    }
}
