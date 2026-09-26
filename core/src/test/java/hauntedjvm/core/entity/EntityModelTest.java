package hauntedjvm.core.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class EntityModelTest {

    @Test
    void decayingValueHalvesTowardBaselineEachHalfLife() {
        Decaying fear = Decaying.at(0.1, 0, 100).bump(0, 0.8);
        assertThat(fear.at(0)).isCloseTo(0.9, within(1e-9));
        assertThat(fear.at(100)).isCloseTo(0.5, within(1e-9));
        assertThat(fear.at(200)).isCloseTo(0.3, within(1e-9));
    }

    @Property
    void decayingStaysInUnitIntervalAndNeverOvershootsBaseline(
            @ForAll @DoubleRange(min = 0, max = 1) double baseline,
            @ForAll @DoubleRange(min = -2, max = 2) double delta,
            @ForAll @LongRange(min = 0, max = 100_000) long later) {
        Decaying d = Decaying.at(baseline, 0, 180).bump(0, delta);
        double v = d.at(later);
        assertThat(v).isBetween(0.0, 1.0);
        assertThat(Math.abs(v - baseline)).isLessThanOrEqualTo(Math.abs(d.value() - baseline) + 1e-12);
    }

    @Test
    void personTransitionsFollowTheStateMachine() {
        assertThat(StateMachine.canTransition(EntityKind.PERSON, EntityState.NORMAL, EntityState.AFRAID)).isTrue();
        assertThat(StateMachine.canTransition(EntityKind.PERSON, EntityState.CORRUPTED, EntityState.NORMAL)).isFalse();
        assertThat(StateMachine.canTransition(EntityKind.PERSON, EntityState.MISSING, EntityState.NORMAL)).isFalse();
        assertThat(StateMachine.canTransition(EntityKind.PERSON, EntityState.MISSING, EntityState.AWARE)).isTrue();
        assertThat(StateMachine.canTransition(EntityKind.PERSON, EntityState.NORMAL, EntityState.RUNNING)).isFalse();
        assertThat(StateMachine.canTransition(EntityKind.UNKNOWN, EntityState.DORMANT, EntityState.MIMICKING)).isFalse();
        assertThat(StateMachine.canTransition(EntityKind.PROCESS, EntityState.RUNNING, EntityState.TERMINATED)).isTrue();
        assertThat(StateMachine.canTransition(EntityKind.ROOM, EntityState.PRESENT, EntityState.PRESENT)).isFalse();
    }

    @Test
    void everyKindHasAtLeastOneValidState() {
        for (EntityKind kind : EntityKind.values()) {
            assertThat(StateMachine.statesFor(kind)).isNotEmpty();
        }
    }

    @Test
    void mindEvictsTheFaintestMemoryWhenFull() {
        Traits traits = new Traits(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
        Mind mind = new Mind(Role.GUARD, traits, Decaying.at(0, 0, 100), Decaying.at(0, 0, 100), List.of(), List.of(),
                null);
        for (int i = 0; i < Mind.MEMORY_CAPACITY; i++) {
            mind = mind.withMemory(trace(i, 0.5 + i * 0.001), 0);
        }
        mind = mind.withMemory(trace(999, 0.9), 0);
        assertThat(mind.memories()).hasSize(Mind.MEMORY_CAPACITY);
        assertThat(mind.memory(0)).isEmpty();
        assertThat(mind.memory(999)).isPresent();
    }

    @Test
    void dreadReflectsTheWorstVividMemoryOfARoom() {
        Traits traits = new Traits(0.5, 0.5, 0.5, 0.5, 0.5, 0.0, 0.5);
        Mind mind = new Mind(Role.ARCHIVIST, traits, Decaying.at(0, 0, 100), Decaying.at(0, 0, 100),
                List.of(new MemoryTrace(1, 0, MemoryKind.SAW_ANOMALY, null, "ROOM-04", null, 1.0, -0.8, -1, "x"),
                        new MemoryTrace(2, 0, MemoryKind.SAW_ENTITY, null, "ROOM-04", null, 1.0, 0.9, -1, "y")),
                List.of(), null);
        assertThat(mind.dread("ROOM-04", 0)).isCloseTo(0.8, within(1e-9));
        assertThat(mind.dread("ROOM-04", (long) traits.memoryHalfLife())).isCloseTo(0.4, within(1e-9));
        assertThat(mind.dread("ROOM-02", 0)).isZero();
    }

    private static MemoryTrace trace(long id, double strength) {
        return new MemoryTrace(id, 0, MemoryKind.SAW_ENTITY, null, "ROOM-01", null, strength, 0, -1, "m" + id);
    }
}
