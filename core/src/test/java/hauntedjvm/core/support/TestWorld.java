package hauntedjvm.core.support;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.SimEvent;
import hauntedjvm.core.state.WorldState;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.FacilityMap;
import hauntedjvm.core.world.Navigator;
import java.util.List;

/**
 * A freshly populated FACILITY-07 that tests can poke at directly: emit arbitrary events,
 * advance the clock, and hand the context to individual systems or helpers.
 */
public final class TestWorld implements TickContext {

    private final SimulationConfig config;
    private final WorldState state;
    private final EventLog log;
    private final Navigator navigator;

    private TestWorld(SimulationConfig config, WorldState state, EventLog log) {
        this.config = config;
        this.state = state;
        this.log = log;
        this.navigator = new Navigator(state.map());
    }

    public static TestWorld genesis(long seed) {
        return genesis(SimulationConfig.defaults(seed));
    }

    public static TestWorld genesis(SimulationConfig config) {
        SimulationEngine engine = Simulations.create(config);
        EventLog copy = new EventLog();
        engine.timeline().log().forEach(0, Long.MAX_VALUE, copy::append);
        return new TestWorld(config, WorldState.restore(FacilityMap.facility07(), engine.snapshot()), copy);
    }

    public WorldState state() {
        return state;
    }

    public void advanceTo(long tick) {
        state.advanceTo(tick);
    }

    public List<Entity> persons() {
        return state.ofKind(EntityKind.PERSON);
    }

    public Entity person(int index) {
        return persons().get(index);
    }

    public Entity refresh(Entity e) {
        return state.entity(e.id());
    }

    @Override
    public WorldView world() {
        return state;
    }

    @Override
    public EventLog log() {
        return log;
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
        EventRecord r = new EventRecord(log.nextSeq(), state.tick(), reportedTick, event);
        log.append(r);
        state.apply(r);
        return r;
    }
}
