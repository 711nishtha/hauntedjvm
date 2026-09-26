package hauntedjvm.core.random;

import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * SplitMix64 generator with counter-based stream derivation.
 *
 * <p>The simulation never keeps a long-lived random generator. Every decision asks for a
 * fresh stream keyed by {@code (seed, tick, purpose, subject)}. That makes each random draw a
 * pure function of <em>where</em> it happens, so:
 * <ul>
 *   <li>a run restored from a snapshot continues identically without serialising RNG state;</li>
 *   <li>adding a new system or entity does not shift the random sequence of unrelated ones;</li>
 *   <li>reordering independent systems does not silently change outcomes.</li>
 * </ul>
 *
 * <p>All derived methods are implemented here rather than inherited from
 * {@link RandomGenerator}'s defaults, because the JDK is free to change those algorithms and a
 * saved session must replay identically on any future JVM.
 */
public final class Rng implements RandomGenerator {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    private Rng(long state) {
        this.state = state;
    }

    /** A plain sequential generator; used for world genesis and in tests. */
    public static Rng seeded(long seed) {
        return new Rng(mix(seed));
    }

    /**
     * Derives an independent stream for one decision point.
     *
     * @param seed    the session seed
     * @param tick    simulation tick at which the decision is made
     * @param purpose a {@link Streams} constant naming the kind of decision
     * @param subject usually an entity serial; anything that distinguishes decisions made in the same tick
     */
    public static Rng stream(long seed, long tick, long purpose, long subject) {
        long h = mix(seed ^ GOLDEN_GAMMA);
        h = mix(h ^ tick * 0xD1B54A32D192ED03L);
        h = mix(h ^ purpose * 0xABC98388FB8FAC03L);
        h = mix(h ^ subject * 0x8CB92BA72F3D8DD7L);
        return new Rng(h);
    }

    /** Stafford's variant 13 finaliser; a strong 64-bit avalanche mix. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public long nextLong() {
        state += GOLDEN_GAMMA;
        return mix(state);
    }

    @Override
    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    /** Uniform in {@code [0, 1)} with 53 bits of precision. */
    @Override
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in {@code [0, bound)}; uses rejection to avoid modulo bias. */
    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long limit = (1L << 31) - ((1L << 31) % bound);
        long r;
        do {
            r = nextLong() >>> 33;
        } while (r >= limit);
        return (int) (r % bound);
    }

    /** Uniform in {@code [origin, bound)}. */
    @Override
    public int nextInt(int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("empty range " + origin + ".." + bound);
        }
        return origin + nextInt(bound - origin);
    }

    /** Uniform in {@code [origin, bound)}. */
    @Override
    public double nextDouble(double origin, double bound) {
        return origin + nextDouble() * (bound - origin);
    }

    @Override
    public boolean nextBoolean() {
        return (nextLong() & 1L) != 0;
    }

    /** True with probability {@code p}; values outside [0,1] are clamped. */
    public boolean chance(double p) {
        if (p <= 0) {
            return false;
        }
        return p >= 1 || nextDouble() < p;
    }

    public <T> T pick(List<T> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("cannot pick from an empty list");
        }
        return items.get(nextInt(items.size()));
    }

    /**
     * Weighted choice. Items with non-positive weight are never chosen.
     *
     * @return the chosen item, or {@code null} if every weight is non-positive
     */
    public <T> T weighted(List<T> items, ToDoubleFunction<? super T> weight) {
        Objects.requireNonNull(weight);
        double total = 0;
        for (T item : items) {
            total += Math.max(0, weight.applyAsDouble(item));
        }
        if (total <= 0) {
            return null;
        }
        double r = nextDouble() * total;
        T last = null;
        for (T item : items) {
            double w = Math.max(0, weight.applyAsDouble(item));
            if (w <= 0) {
                continue;
            }
            last = item;
            r -= w;
            if (r < 0) {
                return item;
            }
        }
        return last;
    }

    /** Roughly normal sample (Irwin–Hall, n=4) clamped to [0, 1]; cheap and fully deterministic. */
    public double bell(double mean, double spread) {
        double s = nextDouble() + nextDouble() + nextDouble() + nextDouble() - 2.0;
        return Math.clamp(mean + s * spread, 0.0, 1.0);
    }
}
