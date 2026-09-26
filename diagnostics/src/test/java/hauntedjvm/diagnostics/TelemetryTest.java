package hauntedjvm.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryTest {

    @Test
    void mxBeanSamplesArePlausible() {
        JvmSnapshot s = new JvmTelemetrySampler().sample(-1, -1);
        assertThat(s.heapUsed()).isPositive();
        assertThat(s.heapCommitted()).isGreaterThanOrEqualTo(s.heapUsed());
        assertThat(s.threads()).isPositive();
        assertThat(s.availableProcessors()).isPositive();
        assertThat(s.uptimeMillis()).isPositive();
        assertThat(s.collectors()).isNotEmpty();
        assertThat(s.vmVersion()).isNotBlank();
        assertThat(s.processCpuLoad()).isBetween(-1.0, 1.0);
        assertThat(s.heapFraction()).isBetween(0.0, 1.0);
    }

    @Test
    void threadInspectorSeesTheCurrentThread() {
        List<ThreadInspector.ThreadView> threads = new ThreadInspector().threads();
        assertThat(threads).extracting(ThreadInspector.ThreadView::id).contains(Thread.currentThread().threadId());
    }

    @Test
    void historyIsBounded() {
        try (TelemetryService service = TelemetryService.start(Duration.ofHours(1), 3, false)) {
            for (int i = 0; i < 10; i++) {
                service.sampleNow();
            }
            assertThat(service.history()).hasSize(3);
            assertThat(service.latest()).isSameAs(service.history().getLast());
            assertThat(service.latest().lastGcPauseMillis()).isEqualTo(-1);
        }
    }

    @Test
    void jfrReportsGarbageCollectionPauses() throws Exception {
        try (JfrMonitor jfr = JfrMonitor.start()) {
            if (!jfr.active()) {
                return;
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            List<byte[]> garbage = new ArrayList<>();
            while (jfr.gcPausesObserved() == 0 && System.nanoTime() < deadline) {
                for (int i = 0; i < 2_000; i++) {
                    garbage.add(new byte[1024]);
                }
                garbage.clear();
                System.gc();
                Thread.sleep(50);
            }
            assertThat(jfr.gcPausesObserved()).isPositive();
            assertThat(jfr.lastGcPauseMillis()).isGreaterThanOrEqualTo(0);
            assertThat(jfr.allocationRate()).isGreaterThanOrEqualTo(0);
        }
    }
}
