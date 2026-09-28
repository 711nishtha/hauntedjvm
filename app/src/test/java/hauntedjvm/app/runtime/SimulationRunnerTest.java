package hauntedjvm.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.event.OperatorEvent;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class SimulationRunnerTest {

    private static SimulationRunner runner(boolean paused) {
        return new SimulationRunner(Simulations.create(SimulationConfig.defaults(3)), Speed.FASTEST, 50, paused);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(5);
        }
    }

    @Test
    void runsInRealTimeAndPublishesFrames() throws Exception {
        try (SimulationRunner r = runner(false)) {
            await(() -> r.latest().status().tick() > 100);
            assertThat(r.latest().snapshot().tick()).isEqualTo(r.latest().status().tick());
            assertThat(r.latest().eventCount()).isLessThanOrEqualTo(r.latest().timeline().log().size());
        }
    }

    @Test
    void pausingStopsTheClockAndSteppingAdvancesIt() throws Exception {
        try (SimulationRunner r = runner(true)) {
            long start = r.latest().status().tick();
            Thread.sleep(150);
            assertThat(r.latest().status().tick()).isEqualTo(start);
            r.step(5);
            await(() -> r.latest().status().tick() == start + 5);
        }
    }

    @Test
    void commandsAreAppliedOnTheSimulationThreadAndAnswered() throws Exception {
        try (SimulationRunner r = runner(true)) {
            OperatorCommand.Result result = r.submit(new OperatorCommand.SwitchCamera("CAM-04"))
                    .get(5, TimeUnit.SECONDS);
            assertThat(result.accepted()).isTrue();
            await(() -> "CAM-04".equals(r.latest().snapshot().incident().observedCamera()));
        }
    }

    @Test
    void rewindForksTheTimelineAndCaptureIsConsistent() throws Exception {
        try (SimulationRunner r = runner(false)) {
            await(() -> r.latest().status().tick() > 300);
            r.setPaused(true);
            await(() -> r.latest().status().paused());
            assertThat(r.rewindTo(100).get(5, TimeUnit.SECONDS)).isTrue();
            SimulationRunner.Cut cut = r.capture().get(5, TimeUnit.SECONDS);
            assertThat(cut.headTick()).isEqualTo(100);
            assertThat(cut.timeline().log().get(cut.eventCount() - 1).event())
                    .isInstanceOf(OperatorEvent.TimelineRewound.class);
            assertThat(r.rewindTo(500).get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void speedPresetsSnapToTheNearestStep() {
        assertThat(Speed.closest(1)).isEqualTo(Speed.NORMAL);
        assertThat(Speed.closest(3)).isEqualTo(Speed.QUADRUPLE);
        assertThat(Speed.closest(100)).isEqualTo(Speed.FASTEST);
        assertThat(Speed.QUARTER.slower()).isEqualTo(Speed.QUARTER);
    }
}
