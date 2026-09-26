package hauntedjvm.diagnostics;

import java.time.Instant;
import java.util.List;

/**
 * One reading of the real JVM this application is running in. Nothing in here is simulated
 * or embellished; values that the platform cannot provide are reported as {@code -1}.
 *
 * @param processCpuLoad     this JVM's share of machine CPU in {@code [0, 1]}, or -1
 * @param systemCpuLoad      whole-machine CPU in {@code [0, 1]}, or -1
 * @param lastGcPauseMillis  duration of the most recent GC pause observed through JFR, or -1
 * @param allocationRate     bytes per second allocated, estimated from JFR allocation samples, or -1
 */
public record JvmSnapshot(
        Instant sampledAt,
        long heapUsed,
        long heapCommitted,
        long heapMax,
        long nonHeapUsed,
        int threads,
        int daemonThreads,
        int peakThreads,
        double processCpuLoad,
        double systemCpuLoad,
        int availableProcessors,
        List<GcStat> collectors,
        long uptimeMillis,
        long loadedClasses,
        long jitMillis,
        String vmName,
        String vmVersion,
        String vmVendor,
        double lastGcPauseMillis,
        double allocationRate) {

    public JvmSnapshot {
        collectors = List.copyOf(collectors);
    }

    /** Per-collector totals since JVM start. */
    public record GcStat(String name, long collections, long timeMillis) {
    }

    public long gcCollections() {
        return collectors.stream().mapToLong(GcStat::collections).sum();
    }

    public long gcTimeMillis() {
        return collectors.stream().mapToLong(GcStat::timeMillis).sum();
    }

    public double heapFraction() {
        long max = heapMax > 0 ? heapMax : heapCommitted;
        return max <= 0 ? 0 : (double) heapUsed / max;
    }
}
