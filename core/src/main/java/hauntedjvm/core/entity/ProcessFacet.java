package hauntedjvm.core.entity;

/**
 * A fictional facility process. None of this is a real OS or JVM process; the heap figures
 * are simulated and are shown in the SIMULATION panel, never the REAL JVM panel.
 *
 * @param claimedPid     the pid the process reports; differs from {@code pid} when impersonating
 * @param terminatedTick -1 while running
 * @param lastActivity   tick of the last allocation; activity after termination is an anomaly
 */
public record ProcessFacet(
        int pid,
        int claimedPid,
        String command,
        String host,
        long heapBytes,
        long startedTick,
        long terminatedTick,
        long lastActivity,
        int restarts,
        String stopReason) implements Facet {

    public ProcessFacet started(long tick) {
        return new ProcessFacet(pid, pid, command, host, 4L << 20, tick, -1, tick,
                terminatedTick >= 0 ? restarts + 1 : restarts, null);
    }

    public ProcessFacet stopped(long tick, String reason) {
        return new ProcessFacet(pid, claimedPid, command, host, heapBytes, startedTick, tick, lastActivity, restarts,
                reason);
    }

    public ProcessFacet allocated(long tick, long bytes) {
        return new ProcessFacet(pid, claimedPid, command, host, Math.max(0, heapBytes + bytes), startedTick,
                terminatedTick, tick, restarts, stopReason);
    }

    public ProcessFacet claiming(int newClaim) {
        return new ProcessFacet(pid, newClaim, command, host, heapBytes, startedTick, terminatedTick, lastActivity,
                restarts, stopReason);
    }

    public boolean terminated() {
        return terminatedTick >= 0;
    }
}
