package hauntedjvm.core.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.OperatorEvent.TimelineRewound;
import org.junit.jupiter.api.Test;

class RewindTest {

    private final SimulationConfig config = SimulationConfig.defaults(2024).withSnapshotInterval(100);

    @Test
    void rewindKeepsThePastAndRecordsTheRewind() {
        SimulationEngine engine = Simulations.create(config);
        engine.run(1_200);
        Timeline before = engine.timeline();
        long keep = before.log().firstSeqAfterTick(450);

        engine.rewindTo(450);

        assertThat(engine.tick()).isEqualTo(450);
        assertThat(engine.timeline()).isNotSameAs(before);
        for (long s = 0; s < keep; s++) {
            assertThat(engine.timeline().log().get(s)).isEqualTo(before.log().get(s));
        }
        EventRecord last = engine.timeline().log().last();
        assertThat(last.event()).isInstanceOf(TimelineRewound.class);
        assertThat(((TimelineRewound) last.event()).fromTick()).isEqualTo(1_200);
        assertThat(engine.world().incident().rewinds()).isEqualTo(1);
        // The discarded future is still intact for anyone holding the old timeline.
        assertThat(before.log().size()).isGreaterThan(engine.timeline().log().size());
    }

    @Test
    void theNewBranchIsItselfDeterministic() {
        SimulationEngine a = Simulations.create(config);
        SimulationEngine b = Simulations.create(config);
        a.run(900);
        b.run(900);
        a.rewindTo(300);
        b.rewindTo(300);
        a.run(600);
        b.run(600);
        assertThat(LogDigest.of(a.timeline().log())).isEqualTo(LogDigest.of(b.timeline().log()));
    }

    @Test
    void theNewBranchCanBeReplayedLikeAnyOther() {
        SimulationEngine engine = Simulations.create(config);
        engine.run(800);
        engine.rewindTo(250);
        engine.run(300);
        var live = engine.snapshot();
        assertThat(engine.timeline().reconstruct(engine.map(), engine.tick()).snapshot()).isEqualTo(live);
    }

    @Test
    void residueOnlyCarriesMemoriesFromTheDiscardedFuture() {
        SimulationEngine engine = Simulations.create(config.withAnomalyIntensity(4.0));
        engine.run(3_000);
        engine.rewindTo(1_000);
        TimelineRewound rewind = (TimelineRewound) engine.timeline().log().last().event();
        assertThat(rewind.residue()).allSatisfy(c -> assertThat(c.trace().tick()).isGreaterThan(1_000));
    }

    @Test
    void cannotRewindIntoTheFuture() {
        SimulationEngine engine = Simulations.create(config);
        engine.run(10);
        assertThatThrownBy(() -> engine.rewindTo(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.rewindTo(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
