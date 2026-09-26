package hauntedjvm.core.engine;

import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.OperatorEvent.CarriedMemory;
import hauntedjvm.core.event.OperatorEvent.TimelineRewound;
import hauntedjvm.core.event.SimEvent;
import hauntedjvm.core.state.WorldSnapshot;
import hauntedjvm.core.state.WorldState;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.timeline.Timeline;
import hauntedjvm.core.world.FacilityMap;
import hauntedjvm.core.world.Navigator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs the tick pipeline over one timeline.
 *
 * <p>An engine is single-threaded by design. Determinism is far easier to guarantee (and to
 * test) when every decision happens in one well-defined order; the interesting concurrency in
 * this project lives around the engine, in how its output is shared, not inside it.
 */
public final class SimulationEngine {

    private final SimulationConfig config;
    private final FacilityMap map;
    private final Navigator navigator;
    private final List<SimulationSystem> systems;
    private final Context context = new Context();
    private Timeline timeline;
    private WorldState state;
    private long lastStepNanos;

    private SimulationEngine(SimulationConfig config, FacilityMap map, List<SimulationSystem> systems,
                             Timeline timeline, WorldState state) {
        this.config = Objects.requireNonNull(config);
        this.map = Objects.requireNonNull(map);
        this.navigator = new Navigator(map);
        this.systems = List.copyOf(systems);
        this.timeline = timeline;
        this.state = state;
    }

    /** A fresh run: genesis at tick 0, first checkpoint taken immediately after. */
    public static SimulationEngine create(SimulationConfig config, FacilityMap map, List<SimulationSystem> systems) {
        WorldState state = WorldState.initial(map);
        SimulationEngine engine = new SimulationEngine(config, map, systems, Timeline.empty(), state);
        FacilityGenesis.populate(engine.context);
        engine.timeline.snapshots().put(state.snapshot());
        return engine;
    }

    /** Continues an existing timeline (a loaded session) from its head. */
    public static SimulationEngine resume(SimulationConfig config, FacilityMap map, List<SimulationSystem> systems,
                                          Timeline timeline) {
        WorldState state = timeline.reconstruct(map, timeline.headTick());
        return new SimulationEngine(config, map, systems, timeline, state);
    }

    /** Advances one tick: every system in order, then a checkpoint if one is due. */
    public void step() {
        long started = System.nanoTime();
        long next = state.tick() + 1;
        state.advanceTo(next);
        for (SimulationSystem system : systems) {
            system.tick(context);
        }
        timeline.advanceHead(next);
        if (next % config.snapshotInterval() == 0) {
            timeline.snapshots().put(state.snapshot());
        }
        lastStepNanos = System.nanoTime() - started;
    }

    public void run(long ticks) {
        for (long i = 0; i < ticks; i++) {
            step();
        }
    }

    /** Validates and records an operator command at the current tick. */
    public OperatorCommand.Result execute(OperatorCommand command) {
        return OperatorCommands.execute(context, command);
    }

    /**
     * Discards everything after {@code tick} and continues from there.
     *
     * <p>Before the future is thrown away, minds that were highly aware at the head get to keep
     * a few of the memories they formed in it. Those memories are recorded in the rewind event
     * itself, so the fork is as reproducible as everything else.
     */
    public void rewindTo(long tick) {
        long from = state.tick();
        if (tick < 0 || tick >= from) {
            throw new IllegalArgumentException("can only rewind into the past: " + tick + " (head " + from + ")");
        }
        List<CarriedMemory> residue = residue(state, tick);
        Timeline forked = timeline.prefixThroughTick(tick);
        WorldState past = forked.reconstruct(map, tick);
        this.timeline = forked;
        this.state = past;
        context.emit(new TimelineRewound(from, tick, residue));
    }

    private static List<CarriedMemory> residue(WorldView head, long toTick) {
        List<CarriedMemory> carried = new ArrayList<>();
        for (Entity e : head.ofKind(EntityKind.PERSON)) {
            if (e.mind().awarenessAt(head.tick()) < 0.45) {
                continue;
            }
            e.mind().memories().stream()
                    .filter(m -> m.tick() > toTick)
                    .filter(m -> m.strengthAt(head.tick(), e.mind().traits().memoryHalfLife()) > 0.3)
                    .sorted((a, b) -> Double.compare(Math.abs(b.valence()), Math.abs(a.valence())))
                    .limit(3)
                    .forEach(m -> carried.add(new CarriedMemory(e.id(), m)));
        }
        return carried;
    }

    public WorldView world() {
        return state;
    }

    public WorldSnapshot snapshot() {
        return state.snapshot();
    }

    public Timeline timeline() {
        return timeline;
    }

    public long tick() {
        return state.tick();
    }

    public SimulationConfig config() {
        return config;
    }

    public FacilityMap map() {
        return map;
    }

    public Navigator navigator() {
        return navigator;
    }

    /** Wall-clock cost of the most recent step, for the SIMULATION telemetry panel. */
    public long lastStepNanos() {
        return lastStepNanos;
    }

    /** The engine's view of itself handed to systems. Delegates so rewinds swap state transparently. */
    private final class Context implements TickContext {
        @Override
        public WorldView world() {
            return state;
        }

        @Override
        public EventLog log() {
            return timeline.log();
        }

        @Override
        public SimulationConfig config() {
            return config;
        }

        @Override
        public Navigator navigator() {
            return navigator;
        }

        @Override
        public EventRecord emit(SimEvent event) {
            return emitDated(event, state.tick());
        }

        @Override
        public EventRecord emitDated(SimEvent event, long reportedTick) {
            EventLog log = timeline.log();
            EventRecord record = new EventRecord(log.nextSeq(), state.tick(), reportedTick, event);
            log.append(record);
            state.apply(record);
            return record;
        }
    }
}
