package hauntedjvm.core.entity;

/**
 * Simulation-local entity handle. Serials are assigned in creation order, which gives the
 * inspector its familiar "#017" numbering and makes sorted iteration deterministic.
 * The globally unique {@link java.util.UUID} lives on the entity itself.
 */
public record EntityId(int value) implements Comparable<EntityId> {

    public EntityId {
        if (value < 0) {
            throw new IllegalArgumentException("entity serial must be non-negative: " + value);
        }
    }

    public static EntityId of(int value) {
        return new EntityId(value);
    }

    @Override
    public int compareTo(EntityId o) {
        return Integer.compare(value, o.value);
    }

    @Override
    public String toString() {
        return String.format("#%03d", value);
    }
}
