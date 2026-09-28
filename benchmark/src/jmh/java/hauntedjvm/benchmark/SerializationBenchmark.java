package hauntedjvm.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.persistence.JsonCodec;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Event (de)serialisation throughput, which bounds how fast sessions save and load. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class SerializationBenchmark {

    private final ObjectMapper mapper = JsonCodec.mapper();
    private EventRecord[] records;
    private byte[][] encoded;
    private int next;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(3));
        engine.run(2_000);
        int n = engine.timeline().log().size();
        records = new EventRecord[n];
        encoded = new byte[n][];
        for (int i = 0; i < n; i++) {
            records[i] = engine.timeline().log().get(i);
            encoded[i] = mapper.writeValueAsBytes(records[i]);
        }
    }

    @Benchmark
    public byte[] write() throws IOException {
        next = (next + 1) % records.length;
        return mapper.writeValueAsBytes(records[next]);
    }

    @Benchmark
    public EventRecord read() throws IOException {
        next = (next + 1) % encoded.length;
        return mapper.readValue(encoded[next], EventRecord.class);
    }
}
