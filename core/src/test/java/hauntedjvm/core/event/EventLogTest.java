package hauntedjvm.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hauntedjvm.core.event.OperatorEvent.SimulationPaused;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EventLogTest {

    private static EventRecord at(long seq, long tick) {
        return new EventRecord(seq, tick, tick, new SimulationPaused());
    }

    @Test
    void appendsAndReadsBackAcrossChunkBoundaries() {
        EventLog log = new EventLog();
        for (int i = 0; i < 10_000; i++) {
            log.append(at(i, i / 10));
        }
        assertThat(log.size()).isEqualTo(10_000);
        assertThat(log.get(4095).seq()).isEqualTo(4095);
        assertThat(log.get(4096).seq()).isEqualTo(4096);
        assertThat(log.last().seq()).isEqualTo(9_999);
    }

    @Test
    void rejectsGapsAndTimeTravel() {
        EventLog log = new EventLog();
        log.append(at(0, 5));
        assertThatThrownBy(() -> log.append(at(2, 5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> log.append(at(1, 4))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findsTickBoundaries() {
        EventLog log = new EventLog();
        long seq = 0;
        for (long tick : new long[] {0, 0, 1, 3, 3, 3, 7}) {
            log.append(at(seq++, tick));
        }
        assertThat(log.firstSeqAfterTick(0)).isEqualTo(2);
        assertThat(log.firstSeqAfterTick(2)).isEqualTo(3);
        assertThat(log.firstSeqAtTick(3)).isEqualTo(3);
        assertThat(log.firstSeqAfterTick(3)).isEqualTo(6);
        assertThat(log.firstSeqAfterTick(100)).isEqualTo(7);
        assertThat(log.prefixThroughTick(3).size()).isEqualTo(6);
    }

    /**
     * One writer, one reader, no locks: the reader must never observe a size whose records are
     * not yet visible.
     */
    @Test
    void concurrentReaderNeverSeesAHole() throws Exception {
        EventLog log = new EventLog();
        int total = 200_000;
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            started.countDown();
            try {
                while (log.size() < total) {
                    int n = log.size();
                    if (n > 0) {
                        EventRecord r = log.get(n - 1);
                        if (r == null || r.seq() != n - 1) {
                            throw new AssertionError("hole at " + (n - 1));
                        }
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        started.await();
        for (int i = 0; i < total; i++) {
            log.append(at(i, i));
        }
        reader.join();
        assertThat(failure.get()).isNull();
    }
}
