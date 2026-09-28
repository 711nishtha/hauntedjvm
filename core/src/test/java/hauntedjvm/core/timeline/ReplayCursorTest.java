package hauntedjvm.core.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.state.WorldState;
import hauntedjvm.core.telemetry.SimulationMetrics;
import org.junit.jupiter.api.Test;

class ReplayCursorTest {

    @Test
    void scrubbingInAnyOrderMatchesDirectReconstruction() {
        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(55));
        engine.run(2_000);
        ReplayCursor cursor = new ReplayCursor(engine.map(), engine.timeline());
        long[] path = {10, 11, 12, 300, 299, 1_500, 1_501, 1_999, 5, 2_000, 2_500, -3};
        for (long t : path) {
            long expected = Math.clamp(t, 0, 2_000);
            WorldState direct = engine.timeline().reconstruct(engine.map(), expected);
            assertThat(((WorldState) cursor.seek(t)).snapshot()).as("tick %d", t).isEqualTo(direct.snapshot());
        }
    }

    @Test
    void metricsDescribeTheWorld() {
        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(7).withAnomalyIntensity(3));
        engine.run(3_000);
        SimulationMetrics m = SimulationMetrics.of(engine.world());
        assertThat(m.tick()).isEqualTo(3_000);
        assertThat(m.personStates().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(16);
        assertThat(m.meanFear()).isBetween(0.0, 1.0);
        assertThat(m.divergence()).isBetween(0.0, 1.0);
        assertThat(m.totalAnomalies()).isGreaterThanOrEqualTo(m.activeAnomalies());
    }
}
