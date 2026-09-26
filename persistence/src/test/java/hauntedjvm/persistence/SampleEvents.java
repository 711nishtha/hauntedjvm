package hauntedjvm.persistence;

import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.Decaying;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.Goal;
import hauntedjvm.core.entity.LightMode;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.Relationship;
import hauntedjvm.core.entity.Role;
import hauntedjvm.core.entity.Traits;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.CommunicationEvent;
import hauntedjvm.core.event.EntityEvent;
import hauntedjvm.core.event.EnvironmentEvent;
import hauntedjvm.core.event.OperatorEvent;
import hauntedjvm.core.event.ProcessEvent;
import hauntedjvm.core.event.SimEvent;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.DetectedAnomaly;
import hauntedjvm.core.incident.Directive;
import hauntedjvm.core.incident.ScheduledPerturbation;
import hauntedjvm.core.world.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One hand-built instance of every event type, used to prove the on-disk format covers them all. */
final class SampleEvents {

    private SampleEvents() {
    }

    static List<SimEvent> all() {
        EntityId a = EntityId.of(3);
        EntityId b = EntityId.of(4);
        Cell c = new Cell(5, 6);
        MemoryTrace trace = new MemoryTrace(0, 12, MemoryKind.HEARD, b, "ROOM-04", c, 0.7, -0.4, 9, "heard a thing");
        Mind mind = new Mind(Role.MEDIC, new Traits(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7),
                Decaying.at(0.1, 0, 180), Decaying.at(0.05, 0, 1500), List.of(trace),
                List.of(new Relationship(b, 0.6, 0.3)), new Goal(c, "ROOM-04", Goal.Reason.INVESTIGATE, b, 90));
        Entity person = new Entity(a, UUID.fromString("0f4c7a1e-8b2d-4c3a-9d1e-2b3c4d5e6f70"), EntityKind.PERSON,
                "MARA", c, c, true, false, EntityState.NORMAL, a, 0.1, false, 0, List.of("echo:#004"), mind);
        DetectedAnomaly anomaly = new DetectedAnomaly("MEMORY:x", AnomalyCategory.MEMORY, 3, 40, List.of(a), "ROOM-04",
                "remembers nothing", List.of(1L, 2L));

        List<SimEvent> events = new ArrayList<>();
        events.add(new EntityEvent.EntityCreated(person, "ROSTER"));
        events.add(new EntityEvent.EntityMoved(a, null, c));
        events.add(new EntityEvent.EntityStateChanged(a, EntityState.NORMAL, EntityState.AFRAID, "fear"));
        events.add(new EntityEvent.GoalChanged(a, null));
        events.add(new EntityEvent.AffectChanged(a, 0.25, -0.01, 0.003, "ambient"));
        events.add(new EntityEvent.EntityObserved(a, b, "ROOM-02"));
        events.add(new EntityEvent.EntityForgotten(a));
        events.add(new EntityEvent.EntityCorrupted(a, 0.51));
        events.add(new EntityEvent.IdentityClaimed(a, b));
        events.add(new EntityEvent.BadgeReported(a, c));
        events.add(new EntityEvent.TrackingChanged(a, false));
        events.add(new EntityEvent.RelationshipChanged(a, b, 0.01, 0.05));
        events.add(new EntityEvent.MemoryFormed(a, trace));
        events.add(new EntityEvent.MemoryFaded(a, 77));
        events.add(new EntityEvent.MarkChanged(a, "lingering", true));
        events.add(new ProcessEvent.ProcessStarted(a, 1200));
        events.add(new ProcessEvent.ProcessStopped(a, 1200, "SIGSEGV"));
        events.add(new ProcessEvent.MemoryAllocated(a, 65_536, "frame-buffer"));
        events.add(new ProcessEvent.MemoryReleased(a, 4_096));
        events.add(new ProcessEvent.PidClaimed(a, 1300));
        events.add(new EnvironmentEvent.DoorOpened(a, null));
        events.add(new EnvironmentEvent.DoorClosed(a));
        events.add(new EnvironmentEvent.DoorLockChanged(a, true, "OPERATOR"));
        events.add(new EnvironmentEvent.DoorUnsealed(a));
        events.add(new EnvironmentEvent.LightChanged(a, LightMode.FLICKER, 99, "no fault logged"));
        events.add(new EnvironmentEvent.CameraStatusChanged(a, CameraStatus.LOOPING, 300, 400));
        events.add(new EnvironmentEvent.ObjectDisplaced(a, c, new Cell(7, 8)));
        events.add(new EnvironmentEvent.RoomRevealed(a));
        events.add(new CommunicationEvent.MessageSent(null, null, CommunicationEvent.Channel.INTERCOM,
                "ROOM-04 IS NOT EMPTY", new CommunicationEvent.Claim("ROOM-04", b, -0.9, 12)));
        events.add(new CommunicationEvent.MessageReceived(55, a));
        events.add(new AnomalyEvent.AnomalyDetected(anomaly));
        events.add(new AnomalyEvent.AnomalyResolved(anomaly.id()));
        events.add(new AnomalyEvent.AnomalyRetracted(anomaly.id(), "RECORD WITHDRAWN"));
        events.add(new AnomalyEvent.AnomalyEscalated(1, 2, 2.75));
        events.add(new AnomalyEvent.PerturbationApplied("door-unattended", AnomalyCategory.VISUAL, List.of(a),
                "ROOM-01", -1, "DOOR-03"));
        events.add(new AnomalyEvent.PerturbationScheduled(new ScheduledPerturbation(9, 100, "vanishing", List.of(a),
                null, "after erasure")));
        events.add(new OperatorEvent.CameraChanged("CAM-01", "CAM-03"));
        events.add(new OperatorEvent.DirectiveIssued(new Directive(Directive.Type.GATHER, "ROOM-02", 10, 250)));
        events.add(new OperatorEvent.OperatorInspected("entity:#003"));
        events.add(new OperatorEvent.SimulationPaused());
        events.add(new OperatorEvent.SimulationResumed());
        events.add(new OperatorEvent.TimelineRewound(900, 300, List.of(new OperatorEvent.CarriedMemory(a, trace))));
        return events;
    }
}
