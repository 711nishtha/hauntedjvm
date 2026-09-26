package hauntedjvm.core.engine;

import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.SimEvent;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.Navigator;

/**
 * What a system may touch during a tick: a read-only world, the history so far, derived random
 * streams, and {@link #emit}. There is deliberately no way to mutate state other than emitting.
 */
public interface TickContext {

    WorldView world();

    EventLog log();

    SimulationConfig config();

    Navigator navigator();

    default long tick() {
        return world().tick();
    }

    /** A random stream unique to this tick, purpose and subject. See {@link Rng#stream}. */
    default Rng rng(long purpose, long subject) {
        return Rng.stream(config().seed(), tick(), purpose, subject);
    }

    /** Records an event and applies it immediately, so later systems in the same tick see it. */
    EventRecord emit(SimEvent event);

    /** As {@link #emit}, but the record claims a different time than the one it was written at. */
    EventRecord emitDated(SimEvent event, long reportedTick);
}
