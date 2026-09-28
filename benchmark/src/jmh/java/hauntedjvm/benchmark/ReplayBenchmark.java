package hauntedjvm.benchmark;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.timeline.ReplayCursor;
import hauntedjvm.core.timeline.Timeline;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * What time travel costs: a random jump (checkpoint restore plus events), a one-tick playback
 * step, and rebuilding an entire timeline from its log as loading a session does.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ReplayBenchmark {

    private static final int TICKS = 6_000;

    private SimulationEngine engine;
    private ReplayCursor cursor;
    private long playhead;
    private final SplittableRandom random = new SplittableRandom(1);

    @Setup(Level.Trial)
    public void setUp() {
        engine = Simulations.create(SimulationConfig.defaults(7).withAnomalyIntensity(2));
        engine.run(TICKS);
        cursor = new ReplayCursor(engine.map(), engine.timeline());
    }

    @Benchmark
    public WorldView randomJump() {
        return engine.timeline().reconstruct(engine.map(), random.nextInt(TICKS));
    }

    @Benchmark
    public WorldView playbackStep() {
        playhead = playhead >= TICKS ? 0 : playhead + 1;
        return cursor.seek(playhead);
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Timeline rebuildWholeTimeline() {
        return Timeline.replay(engine.map(), engine.timeline().log(), engine.tick(), 100, s -> { });
    }
}
