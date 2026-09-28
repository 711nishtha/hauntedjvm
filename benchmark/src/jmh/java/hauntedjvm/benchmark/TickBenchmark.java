package hauntedjvm.benchmark;

import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Simulation ticks per second as the night shift grows. Each trial starts from a world that has
 * already run for a while, so the numbers reflect a settled facility rather than genesis.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class TickBenchmark {

    @Param({"16", "64", "256", "1024"})
    public int persons;

    private SimulationEngine engine;

    @Setup(Level.Trial)
    public void setUp() {
        engine = Simulations.create(SimulationConfig.defaults(42).withPersons(persons));
        engine.run(600);
    }

    @Benchmark
    public long tick() {
        engine.step();
        return engine.tick();
    }
}
