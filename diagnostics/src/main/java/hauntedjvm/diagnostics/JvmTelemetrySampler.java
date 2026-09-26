package hauntedjvm.diagnostics;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the platform MXBeans. Cheap enough to call once a second from any thread.
 *
 * <p>CPU load comes from {@code com.sun.management.OperatingSystemMXBean} when the running JVM
 * provides it (HotSpot and OpenJ9 both do); otherwise it is reported as unavailable rather than
 * approximated.
 */
public final class JvmTelemetrySampler {

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    private final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final CompilationMXBean jit = ManagementFactory.getCompilationMXBean();

    public JvmSnapshot sample(double lastGcPauseMillis, double allocationRate) {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        List<JvmSnapshot.GcStat> gc = new ArrayList<>(collectors.size());
        for (GarbageCollectorMXBean c : collectors) {
            gc.add(new JvmSnapshot.GcStat(c.getName(), Math.max(0, c.getCollectionCount()),
                    Math.max(0, c.getCollectionTime())));
        }
        double processCpu = -1;
        double systemCpu = -1;
        if (os instanceof com.sun.management.OperatingSystemMXBean ext) {
            processCpu = normalise(ext.getProcessCpuLoad());
            systemCpu = normalise(ext.getCpuLoad());
        }
        long jitMillis = jit != null && jit.isCompilationTimeMonitoringSupported() ? jit.getTotalCompilationTime() : -1;
        return new JvmSnapshot(
                Instant.now(),
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                threads.getThreadCount(),
                threads.getDaemonThreadCount(),
                threads.getPeakThreadCount(),
                processCpu,
                systemCpu,
                os.getAvailableProcessors(),
                gc,
                runtime.getUptime(),
                ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                jitMillis,
                runtime.getVmName(),
                runtime.getVmVersion(),
                runtime.getVmVendor(),
                lastGcPauseMillis,
                allocationRate);
    }

    private static double normalise(double load) {
        return load < 0 || Double.isNaN(load) ? -1 : Math.min(1.0, load);
    }
}
