package hauntedjvm.core.state;

import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.entity.Relationship;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.CommunicationEvent;
import hauntedjvm.core.event.EntityEvent;
import hauntedjvm.core.event.EnvironmentEvent;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.OperatorEvent;
import hauntedjvm.core.event.ProcessEvent;
import hauntedjvm.core.incident.AnomalyRecord;
import hauntedjvm.core.world.Cell;
import java.util.function.UnaryOperator;

/**
 * Turns events into state. Every switch here is exhaustive over a sealed interface, so the
 * compiler refuses to build if an event type exists that replay would not understand.
 *
 * <p>The applier is deliberately dumb. It never consults random numbers, never makes decisions
 * and never validates behaviour; it trusts the log. That is what makes the log sufficient to
 * reconstruct any past state.
 */
final class EventApplier {

    /** How long a door stays open after someone walks through. */
    static final int DOOR_OPEN_TICKS = 5;

    private final WorldState state;

    EventApplier(WorldState state) {
        this.state = state;
    }

    void apply(EventRecord r) {
        switch (r.event()) {
            case EntityEvent e -> entity(r, e);
            case ProcessEvent e -> process(r, e);
            case EnvironmentEvent e -> environment(r, e);
            case CommunicationEvent e -> communication(e);
            case AnomalyEvent e -> anomaly(r, e);
            case OperatorEvent e -> operator(r, e);
        }
    }

    private void entity(EventRecord r, EntityEvent event) {
        long tick = r.tick();
        switch (event) {
            case EntityEvent.EntityCreated e -> {
                if (state.entity(e.entity().id()) != null) {
                    throw new IllegalStateException("entity " + e.entity().id() + " already exists");
                }
                state.put(e.entity());
            }
            case EntityEvent.EntityMoved e -> update(e.id(), x -> {
                requireAt(x, e.from(), r);
                return x.withPosition(e.to(), reportedAfterMove(x, e.to()));
            });
            case EntityEvent.EntityStateChanged e -> update(e.id(), x -> e.to() == EntityState.MISSING
                    ? x.withState(e.to()).withPosition(null, x.reportedPosition())
                    : x.withState(e.to()));
            case EntityEvent.GoalChanged e -> updateMind(e.id(), m -> m.withGoal(e.goal()));
            case EntityEvent.AffectChanged e -> update(e.id(), x -> {
                Entity y = x.withCorruption(x.corruption() + e.corruption());
                if (y.facet() instanceof Mind m) {
                    y = y.withFacet(m.withFear(m.fear().bump(tick, e.fear()))
                            .withAwareness(m.awareness().bump(tick, e.awareness())));
                }
                return y;
            });
            case EntityEvent.EntityObserved e -> {
                // Evidence only: the observer's memory is formed by a separate MemoryFormed.
            }
            case EntityEvent.EntityForgotten e -> update(e.id(), x -> x.withForgotten(true));
            case EntityEvent.EntityCorrupted e -> {
                // Milestone marker; the corruption itself arrived as AffectChanged.
            }
            case EntityEvent.IdentityClaimed e -> update(e.id(), x -> x.withIdentity(e.claimed()));
            case EntityEvent.BadgeReported e -> update(e.id(), x -> e.reported() == null
                    ? x.withBadge(x.tracked() ? x.position() : null, false)
                    : x.withBadge(e.reported(), true));
            case EntityEvent.TrackingChanged e -> update(e.id(), x -> x.withTracked(e.tracked())
                    .withBadge(e.tracked() ? x.position() : null, false));
            case EntityEvent.RelationshipChanged e -> updateMind(e.id(), m -> m.withRelationship(
                    m.relationship(e.other()).orElse(new Relationship(e.other(), 0.5, 0.0))
                            .adjust(e.trust(), e.familiarity())));
            case EntityEvent.MemoryFormed e -> {
                updateMind(e.id(), m -> m.withMemory(e.trace().withId(r.seq()), tick));
                Entity subject = state.entity(e.trace().subject());
                if (subject != null && subject.forgotten()) {
                    state.put(subject.withForgotten(false));
                }
            }
            case EntityEvent.MemoryFaded e -> updateMind(e.id(), m -> m.withoutMemory(e.memoryId()));
            case EntityEvent.MarkChanged e -> update(e.id(), x -> x.withMark(e.mark(), e.present()));
        }
    }

    private void process(EventRecord r, ProcessEvent event) {
        long tick = r.tick();
        switch (event) {
            case ProcessEvent.ProcessStarted e -> update(e.process(), x -> x.withState(EntityState.RUNNING)
                    .withFacet(processFacet(x).started(tick)));
            case ProcessEvent.ProcessStopped e -> update(e.process(), x -> x.withState(EntityState.TERMINATED)
                    .withFacet(processFacet(x).stopped(tick, e.reason())));
            case ProcessEvent.MemoryAllocated e -> update(e.process(),
                    x -> x.withFacet(processFacet(x).allocated(tick, e.bytes())));
            case ProcessEvent.MemoryReleased e -> update(e.process(),
                    x -> x.withFacet(processFacet(x).allocated(tick, -e.bytes())));
            case ProcessEvent.PidClaimed e -> update(e.process(),
                    x -> x.withFacet(processFacet(x).claiming(e.claimedPid())));
        }
    }

    private void environment(EventRecord r, EnvironmentEvent event) {
        long tick = r.tick();
        switch (event) {
            case EnvironmentEvent.DoorOpened e -> updateDoor(e.door(), d -> d.opened(tick + DOOR_OPEN_TICKS));
            case EnvironmentEvent.DoorClosed e -> updateDoor(e.door(), DoorFacet::closed);
            case EnvironmentEvent.DoorLockChanged e -> updateDoor(e.door(), d -> d.withLock(e.locked()));
            case EnvironmentEvent.DoorUnsealed e -> updateDoor(e.door(), DoorFacet::unsealed);
            case EnvironmentEvent.LightChanged e -> update(e.room(),
                    x -> x.withFacet(((RoomFacet) x.facet()).withLight(e.mode(), e.until())));
            case EnvironmentEvent.CameraStatusChanged e -> update(e.camera(), x -> x
                    .withFacet(((CameraFacet) x.facet()).withStatus(e.status(), e.timelineOffset(), e.until()))
                    .withState(cameraState(e.status())));
            case EnvironmentEvent.ObjectDisplaced e -> update(e.object(), x -> {
                requireAt(x, e.from(), r);
                return x.withPosition(e.to(), e.to());
            });
            case EnvironmentEvent.RoomRevealed e -> update(e.room(),
                    x -> x.withFacet(((RoomFacet) x.facet()).revealed()));
        }
    }

    private void communication(CommunicationEvent event) {
        switch (event) {
            case CommunicationEvent.MessageSent e -> {
                // Messages change minds only through the MemoryFormed events that follow them.
            }
            case CommunicationEvent.MessageReceived e -> {
                // Delivery receipt; evidence only.
            }
        }
    }

    private void anomaly(EventRecord r, AnomalyEvent event) {
        long tick = r.tick();
        var incident = state.incident();
        switch (event) {
            case AnomalyEvent.AnomalyDetected e -> state.setIncident(
                    incident.withAnomaly(new AnomalyRecord(e.anomaly(), AnomalyRecord.Status.ACTIVE, tick)));
            case AnomalyEvent.AnomalyResolved e -> state.setIncident(
                    incident.withAnomalyStatus(e.anomalyId(), AnomalyRecord.Status.RESOLVED, tick));
            case AnomalyEvent.AnomalyRetracted e -> state.setIncident(
                    incident.withAnomalyStatus(e.anomalyId(), AnomalyRecord.Status.RETRACTED, tick));
            case AnomalyEvent.AnomalyEscalated e -> state.setIncident(incident.withLevel(e.to(), tick));
            case AnomalyEvent.PerturbationApplied e -> state.setIncident(incident.withPerturbation(tick, e.scheduleId()));
            case AnomalyEvent.PerturbationScheduled e -> state.setIncident(incident.withScheduled(e.step()));
        }
    }

    private void operator(EventRecord r, OperatorEvent event) {
        var incident = state.incident();
        switch (event) {
            case OperatorEvent.CameraChanged e -> state.setIncident(incident.withObservedCamera(e.to()));
            case OperatorEvent.DirectiveIssued e -> state.setIncident(incident.withDirective(e.directive()));
            case OperatorEvent.OperatorInspected e -> state.setIncident(incident.withInspection(e.ref(), r.tick()));
            case OperatorEvent.SimulationPaused e -> {
                // Recorded for the evidence trail; the simulation itself does not notice pauses.
            }
            case OperatorEvent.SimulationResumed e -> {
                // As above.
            }
            case OperatorEvent.TimelineRewound e -> {
                state.setIncident(incident.withRewind());
                int i = 0;
                for (OperatorEvent.CarriedMemory carried : e.residue()) {
                    // Negative ids cannot collide with sequence numbers, which is what ordinary memories use.
                    long id = -(r.seq() * 64 + i + 1);
                    i++;
                    updateMind(carried.owner(), m -> m.withMemory(
                            carried.trace().withId(id).withKind(MemoryKind.PREVIOUS_TIMELINE), r.tick()));
                }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * The one consistency check the applier does make: a move must start where the entity is.
     * The engine always records it that way, so a log that disagrees has been damaged or edited,
     * and replaying it would quietly show a different history.
     */
    private static void requireAt(Entity e, Cell from, EventRecord r) {
        if (!java.util.Objects.equals(e.position(), from)) {
            throw new IllegalStateException("record " + r.seq() + " moves " + e.id() + " from " + from
                    + " but it is at " + e.position());
        }
    }

    private Cell reportedAfterMove(Entity e, Cell to) {
        if (!e.tracked()) {
            return null;
        }
        if (e.badgeOverride() || !state.trackerOnline()) {
            return e.reportedPosition();
        }
        return to;
    }

    private static EntityState cameraState(CameraStatus status) {
        return switch (status) {
            case ONLINE -> EntityState.ONLINE;
            case INTERFERENCE, LOOPING -> EntityState.DEGRADED;
            case OFFLINE -> EntityState.OFFLINE;
        };
    }

    private static ProcessFacet processFacet(Entity e) {
        if (e.facet() instanceof ProcessFacet p) {
            return p;
        }
        throw new IllegalStateException(e.id() + " is not a process");
    }

    private void update(EntityId id, UnaryOperator<Entity> change) {
        Entity e = state.entity(id);
        if (e == null) {
            throw new IllegalStateException("event references unknown entity " + id);
        }
        state.put(change.apply(e));
    }

    private void updateMind(EntityId id, UnaryOperator<Mind> change) {
        update(id, e -> e.withFacet(change.apply(e.mind())));
    }

    private void updateDoor(EntityId id, UnaryOperator<DoorFacet> change) {
        update(id, e -> e.withFacet(change.apply((DoorFacet) e.facet())));
    }
}
