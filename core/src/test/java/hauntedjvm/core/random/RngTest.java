package hauntedjvm.core.random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RngTest {

    @Test
    void sameStreamKeyGivesSameSequence() {
        Rng a = Rng.stream(42, 100, Streams.BEHAVIOR, 7);
        Rng b = Rng.stream(42, 100, Streams.BEHAVIOR, 7);
        for (int i = 0; i < 1000; i++) {
            assertThat(a.nextLong()).isEqualTo(b.nextLong());
        }
    }

    @Test
    void anyKeyComponentChangesTheStream() {
        long base = Rng.stream(42, 100, Streams.BEHAVIOR, 7).nextLong();
        assertThat(Rng.stream(43, 100, Streams.BEHAVIOR, 7).nextLong()).isNotEqualTo(base);
        assertThat(Rng.stream(42, 101, Streams.BEHAVIOR, 7).nextLong()).isNotEqualTo(base);
        assertThat(Rng.stream(42, 100, Streams.GOAL, 7).nextLong()).isNotEqualTo(base);
        assertThat(Rng.stream(42, 100, Streams.BEHAVIOR, 8).nextLong()).isNotEqualTo(base);
    }

    /** Pinned values: if these change, every saved session stops replaying. */
    @Test
    void outputIsStableAcrossReleases() {
        assertThat(Rng.mix(1)).isEqualTo(0x5692161D100B05E5L);
        assertThat(Rng.seeded(12345).nextLong()).isEqualTo(0x7FB6FC5796D17578L);
        assertThat(Rng.stream(1, 2, 3, 4).nextLong()).isEqualTo(0x9C6D37F1549CE403L);
    }

    @Property
    void boundedIntsStayInRange(@ForAll long seed, @ForAll @IntRange(min = 1, max = 10_000) int bound) {
        Rng rng = Rng.seeded(seed);
        for (int i = 0; i < 50; i++) {
            assertThat(rng.nextInt(bound)).isBetween(0, bound - 1);
        }
    }

    @Property
    void doublesAreInUnitInterval(@ForAll long seed) {
        Rng rng = Rng.seeded(seed);
        for (int i = 0; i < 50; i++) {
            assertThat(rng.nextDouble()).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
        }
    }

    @Test
    void weightedNeverPicksZeroWeight() {
        Rng rng = Rng.seeded(9);
        List<String> items = List.of("never", "always");
        for (int i = 0; i < 500; i++) {
            assertThat(rng.weighted(items, s -> s.equals("never") ? 0 : 1)).isEqualTo("always");
        }
        assertThat(rng.weighted(items, s -> 0)).isNull();
    }

    @Test
    void weightedRoughlyFollowsWeights() {
        Rng rng = Rng.seeded(11);
        List<Integer> items = List.of(1, 3);
        long threes = IntStream.range(0, 20_000).filter(i -> rng.weighted(items, x -> x) == 3).count();
        assertThat(threes / 20_000.0).isBetween(0.72, 0.78);
    }

    @Test
    void rejectsNonPositiveBound() {
        assertThatThrownBy(() -> Rng.seeded(1).nextInt(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
