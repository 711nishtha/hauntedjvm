package hauntedjvm.diagnostics;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples real JVM telemetry on a schedule and keeps a short history for sparklines.
 *
 * <p>Sampling is blocking-free and tiny, so it runs on a single virtual thread; there is no
 * reason to pin a platform thread to wake up once a second. Readers (the UI) take the latest
 * sample from an {@link AtomicReference} and a copy of the history, never blocking the sampler.
 */
public final class TelemetryService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryService.class);

    private final JvmTelemetrySampler sampler = new JvmTelemetrySampler();
    private final JfrMonitor jfr;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<JvmSnapshot> latest = new AtomicReference<>();
    private final Deque<JvmSnapshot> history = new ArrayDeque<>();
    private final int historySize;

    private TelemetryService(JfrMonitor jfr, Duration period, int historySize) {
        this.jfr = jfr;
        this.historySize = historySize;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("jvm-telemetry").factory());
        sampleNow();
        scheduler.scheduleAtFixedRate(this::sampleSafely, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * @param useJfr whether to start JFR streaming for GC pause and allocation figures
     */
    public static TelemetryService start(Duration period, int historySize, boolean useJfr) {
        return new TelemetryService(useJfr ? JfrMonitor.start() : null, period, historySize);
    }

    public JvmSnapshot latest() {
        return latest.get();
    }

    public List<JvmSnapshot> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public boolean jfrActive() {
        return jfr != null && jfr.active();
    }

    /** Takes a sample immediately on the calling thread. */
    public JvmSnapshot sampleNow() {
        JvmSnapshot s = sampler.sample(jfr == null ? -1 : jfr.lastGcPauseMillis(), jfr == null ? -1 : jfr.allocationRate());
        latest.set(s);
        synchronized (history) {
            history.addLast(s);
            while (history.size() > historySize) {
                history.removeFirst();
            }
        }
        return s;
    }

    private void sampleSafely() {
        try {
            sampleNow();
        } catch (RuntimeException e) {
            // A failing sample must not cancel the schedule.
            LOG.atWarn().setCause(e).log("telemetry sample failed");
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (jfr != null) {
            jfr.close();
        }
    }
}
