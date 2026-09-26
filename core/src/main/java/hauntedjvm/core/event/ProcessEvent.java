package hauntedjvm.core.event;

import hauntedjvm.core.entity.EntityId;
import java.util.List;

/** Lifecycle of the facility's fictional processes. */
public sealed interface ProcessEvent extends SimEvent {

    EntityId process();

    @Override
    default List<EntityId> subjects() {
        return SimEvent.of(process());
    }

    record ProcessStarted(EntityId process, int pid) implements ProcessEvent {
    }

    record ProcessStopped(EntityId process, int pid, String reason) implements ProcessEvent {
    }

    /** Simulated heap only. Real JVM allocation is reported separately by the diagnostics module. */
    record MemoryAllocated(EntityId process, long bytes, String label) implements ProcessEvent {
    }

    record MemoryReleased(EntityId process, long bytes) implements ProcessEvent {
    }

    record PidClaimed(EntityId process, int claimedPid) implements ProcessEvent {
    }
}
