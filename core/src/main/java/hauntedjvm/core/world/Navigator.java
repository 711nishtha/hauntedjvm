package hauntedjvm.core.world;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Grid navigation with cached flow fields.
 *
 * <p>Instead of running A* per entity per step, the navigator computes a breadth-first distance
 * field outward from a destination once; any number of entities heading there then just step
 * downhill. Destinations are restricted to room anchors, so a few dozen fields cover a whole
 * night shift. Fields are keyed by destination and door mask, so a lock change simply produces
 * a different key rather than an invalidation problem.
 *
 * <p>The cache is a pure memo: evicting or clearing it never changes a result, which keeps the
 * simulation deterministic regardless of cache history. Instances are not thread-safe; each
 * simulation engine owns one.
 */
public final class Navigator {

    private static final int UNREACHABLE = Integer.MAX_VALUE;
    private static final int[][] DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final int CACHE_SIZE = 384;

    private record Key(Cell goal, long blockedDoors) {
    }

    private final FacilityMap map;
    private final Map<Key, int[]> fields = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, int[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private long computed;

    public Navigator(FacilityMap map) {
        this.map = map;
    }

    /**
     * The next cell on a shortest path from {@code from} toward {@code goal}.
     *
     * @return the neighbouring cell to step onto, {@code from} itself when already at the goal,
     *         or {@code null} when the goal is unreachable under the given door mask
     */
    public Cell nextStep(Cell from, Cell goal, long blockedDoors) {
        if (from.equals(goal)) {
            return from;
        }
        int[] field = field(goal, blockedDoors);
        int here = field[index(from)];
        if (here == UNREACHABLE) {
            return null;
        }
        Cell best = null;
        int bestDistance = here;
        for (int[] d : DIRECTIONS) {
            Cell n = from.offset(d[0], d[1]);
            if (!map.contains(n)) {
                continue;
            }
            int dist = field[index(n)];
            if (dist < bestDistance) {
                bestDistance = dist;
                best = n;
            }
        }
        return best;
    }

    /** Path length in steps, or -1 if unreachable. */
    public int distance(Cell from, Cell goal, long blockedDoors) {
        int d = field(goal, blockedDoors)[index(from)];
        return d == UNREACHABLE ? -1 : d;
    }

    public boolean reachable(Cell from, Cell goal, long blockedDoors) {
        return distance(from, goal, blockedDoors) >= 0;
    }

    /** Number of flow fields computed so far; exposed for telemetry and benchmarks. */
    public long fieldsComputed() {
        return computed;
    }

    private int[] field(Cell goal, long blockedDoors) {
        return fields.computeIfAbsent(new Key(goal, blockedDoors), k -> compute(k.goal(), k.blockedDoors()));
    }

    private int[] compute(Cell goal, long blockedDoors) {
        computed++;
        int[] dist = new int[map.width() * map.height()];
        Arrays.fill(dist, UNREACHABLE);
        if (!map.passable(goal, blockedDoors)) {
            return dist;
        }
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        dist[index(goal)] = 0;
        queue.add(goal);
        while (!queue.isEmpty()) {
            Cell c = queue.poll();
            int next = dist[index(c)] + 1;
            for (int[] d : DIRECTIONS) {
                Cell n = c.offset(d[0], d[1]);
                if (map.contains(n) && dist[index(n)] == UNREACHABLE && map.passable(n, blockedDoors)) {
                    dist[index(n)] = next;
                    queue.add(n);
                }
            }
        }
        return dist;
    }

    private int index(Cell c) {
        return c.y() * map.width() + c.x();
    }
}
