package hauntedjvm.core.entity;

/** How one mind regards another. Both values in {@code [0, 1]}. */
public record Relationship(EntityId other, double trust, double familiarity) {

    public Relationship {
        trust = Math.clamp(trust, 0.0, 1.0);
        familiarity = Math.clamp(familiarity, 0.0, 1.0);
    }

    public Relationship adjust(double trustDelta, double familiarityDelta) {
        return new Relationship(other, trust + trustDelta, familiarity + familiarityDelta);
    }
}
