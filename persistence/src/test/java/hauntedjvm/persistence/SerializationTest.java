package hauntedjvm.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import hauntedjvm.core.Simulations;
import hauntedjvm.core.config.SimulationConfig;
import hauntedjvm.core.engine.SimulationEngine;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.SimEvent;
import hauntedjvm.core.state.WorldSnapshot;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SerializationTest {

    private final ObjectMapper mapper = JsonCodec.mapper();

    private static Set<Class<?>> leaves(Class<?> root) {
        Set<Class<?>> out = new HashSet<>();
        for (Class<?> sub : root.getPermittedSubclasses()) {
            if (sub.isSealed()) {
                out.addAll(leaves(sub));
            } else {
                out.add(sub);
            }
        }
        return out;
    }

    /** Fails when someone adds an event type without adding a sample for it here. */
    @Test
    void theSamplesCoverEveryEventType() {
        Set<Class<?>> sampled = new HashSet<>();
        SampleEvents.all().forEach(e -> sampled.add(e.getClass()));
        assertThat(sampled).containsExactlyInAnyOrderElementsOf(leaves(SimEvent.class));
    }

    @Test
    void everyEventTypeRoundTrips() throws Exception {
        long seq = 0;
        for (SimEvent event : SampleEvents.all()) {
            EventRecord record = new EventRecord(seq++, 42, 42 - seq, event);
            String json = mapper.writeValueAsString(record);
            assertThat(mapper.readValue(json, EventRecord.class)).as(json).isEqualTo(record);
        }
    }

    @Test
    void theFormatIsReadable() throws Exception {
        String json = mapper.writeValueAsString(new EventRecord(7, 10, 10,
                new hauntedjvm.core.event.EntityEvent.EntityMoved(hauntedjvm.core.entity.EntityId.of(3), null,
                        new hauntedjvm.core.world.Cell(1, 2))));
        assertThat(json).isEqualTo("{\"seq\":7,\"tick\":10,\"reportedTick\":10,\"event\":"
                + "{\"@type\":\"EntityMoved\",\"id\":3,\"from\":null,\"to\":{\"x\":1,\"y\":2}}}");
    }

    @Test
    void aRealRunRoundTripsRecordForRecord() throws Exception {
        SimulationEngine engine = Simulations.create(SimulationConfig.defaults(8).withAnomalyIntensity(3));
        engine.run(3_000);
        var log = engine.timeline().log();
        for (long s = 0; s < log.size(); s++) {
            EventRecord r = log.get(s);
            assertThat(mapper.readValue(mapper.writeValueAsBytes(r), EventRecord.class)).isEqualTo(r);
        }
        WorldSnapshot snap = engine.snapshot();
        assertThat(mapper.readValue(mapper.writeValueAsBytes(snap), WorldSnapshot.class)).isEqualTo(snap);
    }
}
