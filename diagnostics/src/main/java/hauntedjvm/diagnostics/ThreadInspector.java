package hauntedjvm.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists the real platform threads of this JVM for the inspector's thread view.
 *
 * <p>Virtual threads are not visible through {@link ThreadMXBean} by design; the view says so
 * rather than pretending the list is complete.
 */
public final class ThreadInspector {

    /** One real thread. {@code cpuNanos} is -1 where thread CPU time is unsupported. */
    public record ThreadView(long id, String name, Thread.State state, boolean daemon, long cpuNanos,
                             long blockedCount, long waitedCount, String lockName) {
    }

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

    public List<ThreadView> threads() {
        boolean cpu = threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
        List<ThreadView> out = new ArrayList<>();
        for (ThreadInfo info : threads.dumpAllThreads(false, false)) {
            if (info == null) {
                continue;
            }
            long id = info.getThreadId();
            out.add(new ThreadView(id, info.getThreadName(), info.getThreadState(), info.isDaemon(),
                    cpu ? threads.getThreadCpuTime(id) : -1, info.getBlockedCount(), info.getWaitedCount(),
                    info.getLockName()));
        }
        out.sort(Comparator.comparing(ThreadView::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }
}
