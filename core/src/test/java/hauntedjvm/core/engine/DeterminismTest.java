package hauntedjvm.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.timeline.LogDigest;
import org.junit.jupiter.api.Test;

class DeterminismTest {

    private static final long TICKS = 2_500;

    @Test
    void sameSeedRecordsTheSameHistory() {
        SimulationEngine a = Simulations.create(SimulationConfig.defaults(1337));
        SimulationEngine b = Simulations.create(SimulationConfig.defaults(1337));
        a.run(TICKS);
        b.run(TICKS);
        assertThat(a.timeline().log().size()).isEqualTo(b.timeline().log().size());
        assertThat(LogDigest.of(a.timeline().log())).isEqualTo(LogDigest.of(b.timeline().log()));
        assertThat(a.snapshot()).isEqualTo(b.snapshot());
    }

    @Test
    void differentSeedsDiverge() {
        SimulationEngine a = Simulations.create(SimulationConfig.defaults(1));
        SimulationEngine b = Simulations.create(SimulationConfig.defaults(2));
        a.run(500);
        b.run(500);
        assertThat(LogDigest.of(a.timeline().log())).isNotEqualTo(LogDigest.of(b.timeline().log()));
    }

    /**
     * Systems may keep no hidden state: an engine resumed from a timeline must continue exactly
     * as the original would have.
     */
    @Test
    void resumingFromATimelineContinuesIdentically() {
        SimulationConfig config = SimulationConfig.defaults(99).withSnapshotInterval(64);
        SimulationEngine original = Simulations.create(config);
        original.run(TICKS);

        SimulationEngine interrupted = Simulations.create(config);
        interrupted.run(1_111);
        SimulationEngine resumed = Simulations.resume(config, interrupted.timeline());
        resumed.run(TICKS - 1_111);

        assertThat(LogDigest.of(resumed.timeline().log())).isEqualTo(LogDigest.of(original.timeline().log()));
        assertThat(resumed.snapshot()).isEqualTo(original.snapshot());
    }

    @Test
    void operatorInputIsPartOfTheHistory() {
        SimulationEngine a = Simulations.create(SimulationConfig.defaults(5));
        SimulationEngine b = Simulations.create(SimulationConfig.defaults(5));
        a.run(300);
        b.run(300);
        a.execute(new OperatorCommand.SwitchCamera("CAM-03"));
        b.execute(new OperatorCommand.SwitchCamera("CAM-03"));
        a.run(700);
        b.run(700);
        assertThat(LogDigest.of(a.timeline().log())).isEqualTo(LogDigest.of(b.timeline().log()));
    }
}
