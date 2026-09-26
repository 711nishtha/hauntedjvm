package hauntedjvm.diagnostics;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process JDK Flight Recorder streaming for the two figures MXBeans cannot give:
 * individual GC pause durations and the allocation rate.
 *
 * <p>JFR runs on its own thread and hands events to the callbacks below; readers on other
 * threads see the latest values through atomics and a small synchronised window. If JFR is
 * unavailable in this runtime the monitor simply reports nothing (every figure stays -1).
 * No recording is written to disk.
 */
public final class JfrMonitor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JfrMonitor.class);
    private static final long WINDOW_NANOS = Duration.ofSeconds(5).toNanos();

    private record Sample(long atNanos, long bytes) {
    }

    private final RecordingStream stream;
    private final AtomicLong lastPauseMicros = new AtomicLong(-1);
    private final AtomicLong pauses = new AtomicLong();
    private final Deque<Sample> allocations = new ArrayDeque<>();

    private JfrMonitor(RecordingStream stream) {
        this.stream = stream;
    }

    /** Starts streaming, or returns a monitor that reports nothing if JFR cannot be used here. */
    public static JfrMonitor start() {
        try {
            RecordingStream rs = new RecordingStream();
            JfrMonitor monitor = new JfrMonitor(rs);
            rs.enable("jdk.GarbageCollection");
            rs.enable("jdk.ObjectAllocationSample").withPeriod(Duration.ofMillis(20));
            rs.onEvent("jdk.GarbageCollection", monitor::onGc);
            rs.onEvent("jdk.ObjectAllocationSample", monitor::onAllocation);
            rs.setMaxAge(Duration.ofSeconds(10));
            rs.startAsync();
            LOG.atInfo().log("JFR event streaming started (GC pauses, allocation samples)");
            return monitor;
        } catch (RuntimeException | Error e) {
            LOG.atWarn().addKeyValue("reason", e.toString()).log("JFR unavailable; GC pause and allocation figures disabled");
            return new JfrMonitor(null);
        }
    }

    public boolean active() {
        return stream != null;
    }

    private void onGc(RecordedEvent e) {
        lastPauseMicros.set(e.getDuration("longestPause").toNanos() / 1_000);
        pauses.incrementAndGet();
    }

    private void onAllocation(RecordedEvent e) {
        long now = System.nanoTime();
        synchronized (allocations) {
            allocations.addLast(new Sample(now, e.getLong("weight")));
            trim(now);
        }
    }

    private void trim(long now) {
        while (!allocations.isEmpty() && now - allocations.peekFirst().atNanos() > WINDOW_NANOS) {
            allocations.removeFirst();
        }
    }

    /** Longest pause of the most recent collection, in milliseconds, or -1 before the first one. */
    public double lastGcPauseMillis() {
        long micros = lastPauseMicros.get();
        return micros < 0 ? -1 : micros / 1_000.0;
    }

    public long gcPausesObserved() {
        return pauses.get();
    }

    /** Bytes per second over the last few seconds, or -1 if JFR is not running. */
    public double allocationRate() {
        if (stream == null) {
            return -1;
        }
        long now = System.nanoTime();
        synchronized (allocations) {
            trim(now);
            long total = 0;
            for (Sample s : allocations) {
                total += s.bytes();
            }
            return total / (WINDOW_NANOS / 1e9);
        }
    }

    @Override
    public void close() {
        if (stream != null) {
            stream.close();
        }
    }
}
