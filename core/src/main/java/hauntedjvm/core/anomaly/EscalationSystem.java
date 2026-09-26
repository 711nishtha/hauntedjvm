package hauntedjvm.core.anomaly;

import hauntedjvm.core.engine.SimulationSystem;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.event.AnomalyEvent.AnomalyEscalated;
import hauntedjvm.core.event.AnomalyEvent.AnomalyResolved;
import hauntedjvm.core.event.CommunicationEvent.Channel;
import hauntedjvm.core.event.CommunicationEvent.MessageSent;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.incident.EscalationLevel;
import hauntedjvm.core.incident.IncidentState;
import hauntedjvm.core.state.WorldView;

/** Moves the escalation level and retires anomalies that have aged out. */
public final class EscalationSystem implements SimulationSystem {

    private static final int PERIOD = 10;

    @Override
    public String name() {
        return "escalation";
    }

    @Override
    public void tick(TickContext ctx) {
        long t = ctx.tick();
        if (t % PERIOD != 0) {
            return;
        }
        WorldView w = ctx.world();
        IncidentState incident = w.incident();
        for (AnomalyRecord r : incident.anomalies()) {
            if (r.active() && t - r.statusTick() > 900 + 300L * r.anomaly().severity()) {
                ctx.emit(new AnomalyResolved(r.anomaly().id()));
            }
        }
        double instability = Escalation.instability(w);
        int current = w.incident().level();
        int next = Escalation.nextLevel(current, instability, t - w.incident().levelSince());
        if (next != current) {
            ctx.emit(new AnomalyEscalated(current, next, instability));
            Entity log = w.process("incident-log");
            boolean logging = log != null && log.state() == hauntedjvm.core.entity.EntityState.RUNNING;
            ctx.emit(new MessageSent(logging ? log.id() : null, null, Channel.INCIDENT_LOG,
                    "INCIDENT LEVEL " + (next > current ? "RAISED" : "LOWERED") + ": "
                            + EscalationLevel.of(next).label(), null));
        }
    }
}
