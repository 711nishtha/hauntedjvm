package hauntedjvm.core.anomaly;

import hauntedjvm.core.engine.SimulationSystem;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.event.AnomalyEvent.AnomalyDetected;
import hauntedjvm.core.event.CommunicationEvent.Channel;
import hauntedjvm.core.event.CommunicationEvent.MessageSent;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.incident.DetectedAnomaly;
import hauntedjvm.core.state.WorldView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the detector every few ticks over the ticks completed since the last scan, and records
 * whatever is new. Serious findings are also written to the incident log channel, by the
 * {@code incident-log} process if it is running and by nobody if it is not.
 */
public final class DetectionSystem implements SimulationSystem {

    static final int PERIOD = 5;
    private static final int NOTICE_SEVERITY = 3;

    @Override
    public String name() {
        return "detection";
    }

    @Override
    public void tick(TickContext ctx) {
        long t = ctx.tick();
        if (t % PERIOD != 0) {
            return;
        }
        WorldView w = ctx.world();
        EventLog log = ctx.log();
        // Whole ticks only: [t - PERIOD, t). Events of the current tick are still being written.
        long from = log.firstSeqAtTick(t - PERIOD);
        long to = log.firstSeqAtTick(t);
        List<DetectedAnomaly> found = AnomalyDetector.scan(w, log, from, to);
        Set<String> reported = new HashSet<>();
        for (DetectedAnomaly a : found) {
            if (!reported.add(a.key()) || w.incident().hasActiveKey(a.key())) {
                continue;
            }
            ctx.emit(new AnomalyDetected(a));
            if (a.severity() >= NOTICE_SEVERITY) {
                Entity logger = w.process("incident-log");
                boolean running = logger != null && logger.state() == EntityState.RUNNING;
                ctx.emit(new MessageSent(running ? logger.id() : null, null, Channel.INCIDENT_LOG,
                        "ANOMALY LOGGED [" + a.category() + "/" + a.severity() + "] " + a.summary(), null));
            }
        }
    }
}
