package hauntedjvm.core.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Append-only event history with lock-free reads.
 *
 * <p>Exactly one thread (the simulation thread) appends; any number of threads (renderer,
 * reconstructor, persistence) read concurrently. Records live in fixed-size chunks that are
 * never moved once written, and the size is published through a volatile write after the
 * record is in place. A reader that observes {@code size == n} is therefore guaranteed to see
 * records {@code 0..n-1} fully initialised, without taking a lock.
 *
 * <p>Rewinding does not truncate a log in place, because readers may be iterating it. Instead
 * {@link #prefixThroughTick} produces a new log, and the timeline swaps the reference.
 */
public final class EventLog {

    private static final int CHUNK_BITS = 12;
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private volatile EventRecord[][] chunks = new EventRecord[16][];
    private volatile int size;

    /** Appends a record. Must only be called from the owning writer thread. */
    public void append(EventRecord record) {
        int index = size;
        if (record.seq() != index) {
            throw new IllegalArgumentException("expected seq " + index + " but got " + record.seq());
        }
        if (index > 0 && record.tick() < get(index - 1).tick()) {
            throw new IllegalArgumentException("ticks must not decrease: " + record.tick() + " after "
                    + get(index - 1).tick());
        }
        int chunk = index >>> CHUNK_BITS;
        EventRecord[][] dir = chunks;
        if (chunk >= dir.length) {
            dir = Arrays.copyOf(dir, dir.length * 2);
        }
        if (dir[chunk] == null) {
            dir[chunk] = new EventRecord[CHUNK_SIZE];
        }
        dir[chunk][index & CHUNK_MASK] = record;
        chunks = dir;
        size = index + 1;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Sequence number the next appended record must carry. */
    public long nextSeq() {
        return size;
    }

    public EventRecord get(long seq) {
        int n = size;
        if (seq < 0 || seq >= n) {
            throw new IndexOutOfBoundsException("seq " + seq + " outside [0," + n + ")");
        }
        int i = (int) seq;
        return chunks[i >>> CHUNK_BITS][i & CHUNK_MASK];
    }

    public boolean contains(long seq) {
        return seq >= 0 && seq < size;
    }

    public EventRecord last() {
        int n = size;
        return n == 0 ? null : get(n - 1);
    }

    /** Visits records with {@code fromSeq <= seq < toSeq}, clipped to the current size. */
    public void forEach(long fromSeq, long toSeq, Consumer<EventRecord> action) {
        int n = size;
        long end = Math.min(toSeq, n);
        for (long s = Math.max(0, fromSeq); s < end; s++) {
            action.accept(get(s));
        }
    }

    public List<EventRecord> range(long fromSeq, long toSeq) {
        List<EventRecord> out = new ArrayList<>();
        forEach(fromSeq, toSeq, out::add);
        return out;
    }

    /** Sequence number of the first record appended after {@code tick}, or {@link #size()} if none. */
    public long firstSeqAfterTick(long tick) {
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (get(mid).tick() <= tick) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Sequence number of the first record appended at or after {@code tick}. */
    public long firstSeqAtTick(long tick) {
        return firstSeqAfterTick(tick - 1);
    }

    /** A new log containing only records appended at or before {@code tick}. */
    public EventLog prefixThroughTick(long tick) {
        EventLog copy = new EventLog();
        long end = firstSeqAfterTick(tick);
        forEach(0, end, copy::append);
        return copy;
    }
}
