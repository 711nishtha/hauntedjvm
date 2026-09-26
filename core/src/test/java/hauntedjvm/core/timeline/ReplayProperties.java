package hauntedjvm.core.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.state.WorldSnapshot;
import hauntedjvm.core.state.WorldState;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * The central replay guarantee: for any seed and any tick, checkpoint + subsequent events
 * reconstructs exactly the state the live simulation had at that tick.
 */
class ReplayProperties {

    @Property(tries = 12)
    void reconstructionMatchesTheLiveWorld(@ForAll @LongRange(min = 0, max = 1_000_000) long seed,
                                           @ForAll @IntRange(min = 0, max = 1_400) int target,
                                           @ForAll @IntRange(min = 7, max = 150) int snapshotInterval) {
        SimulationConfig config = SimulationConfig.defaults(seed).withSnapshotInterval(snapshotInterval);
        SimulationEngine engine = Simulations.create(config);
        WorldSnapshot live = target == 0 ? engine.snapshot() : null;
        for (int t = 1; t <= 1_400; t++) {
            engine.step();
            if (t == target) {
                live = engine.snapshot();
            }
        }
        WorldState rebuilt = engine.timeline().reconstruct(engine.map(), target);
        assertThat(rebuilt.snapshot()).isEqualTo(live);
    }

    /** Operator commands issued while paused land after that tick's checkpoint; replay must still include them. */
    @Property(tries = 6)
    void commandsBetweenTicksAreReplayed(@ForAll @LongRange(min = 0, max = 1_000) long seed) {
        SimulationConfig config = SimulationConfig.defaults(seed).withSnapshotInterval(50);
        SimulationEngine engine = Simulations.create(config);
        engine.run(200);
        engine.execute(new OperatorCommand.SetLight("ROOM-04", LightMode.OFF));
        engine.execute(new OperatorCommand.SwitchCamera("CAM-05"));
        WorldSnapshot live = engine.snapshot();
        engine.run(100);
        assertThat(engine.timeline().reconstruct(engine.map(), 200).snapshot()).isEqualTo(live);
    }
}
