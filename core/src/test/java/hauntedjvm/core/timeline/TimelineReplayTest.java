package hauntedjvm.core.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.OperatorCommand;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.state.WorldSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineReplayTest {

    private SimulationEngine recorded() {
        SimulationEngine e = Simulations.create(SimulationConfig.defaults(404).withSnapshotInterval(100));
        e.run(700);
        e.execute(new OperatorCommand.SwitchCamera("CAM-06"));
        e.execute(new OperatorCommand.SetLight("ROOM-09", LightMode.DIM));
        e.run(800);
        return e;
    }

    @Test
    void aTimelineRebuiltFromTheLogAloneReconstructsEveryTick() {
        SimulationEngine e = recorded();
        List<WorldSnapshot> rebuilt = new ArrayList<>();
        Timeline replayed = Timeline.replay(e.map(), e.timeline().log(), e.tick(), 250, rebuilt::add);

        assertThat(rebuilt).extracting(WorldSnapshot::tick).containsExactly(0L, 250L, 500L, 750L, 1000L, 1250L, 1500L);
        for (long t : new long[] {0, 1, 99, 250, 700, 701, 1234, 1500}) {
            assertThat(replayed.reconstruct(e.map(), t).snapshot())
                    .as("tick %d", t).isEqualTo(e.timeline().reconstruct(e.map(), t).snapshot());
        }
    }

    @Test
    void recordedCheckpointsSurviveVerification() {
        SimulationEngine e = recorded();
        assertThat(ReplayVerifier.verify(e.map(), e.timeline().log(), e.timeline().snapshots().all())).isEmpty();
    }

    @Test
    void aTamperedCheckpointIsCaught() {
        SimulationEngine e = recorded();
        List<WorldSnapshot> checkpoints = new ArrayList<>(e.timeline().snapshots().all());
        WorldSnapshot original = checkpoints.get(3);
        checkpoints.set(3, new WorldSnapshot(original.tick(), original.lastSeq(), original.nextSerial(),
                original.entities().subList(1, original.entities().size()), original.incident()));
        assertThat(ReplayVerifier.verify(e.map(), e.timeline().log(), checkpoints))
                .singleElement().extracting(ReplayVerifier.Mismatch::tick).isEqualTo(original.tick());
    }
}
