package hauntedjvm.app.ui;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.CommunicationEvent;
import hauntedjvm.core.event.EntityEvent;
import hauntedjvm.core.event.EnvironmentEvent;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.OperatorEvent;
import hauntedjvm.core.event.ProcessEvent;
import hauntedjvm.core.event.SimEvent;
import hauntedjvm.core.incident.EscalationLevel;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.time.FacilityClock;

/** Human-readable descriptions of events, for the log and the inspector. */
public final class EventText {

    /** Coarse grouping used by the log's filters. */
    public enum Group {
        PEOPLE, SYSTEM, FACILITY, COMMS, INCIDENT, OPERATOR
    }

    private EventText() {
    }

    public static Group group(SimEvent e) {
        return switch (e) {
            case EntityEvent x -> Group.PEOPLE;
            case ProcessEvent x -> Group.SYSTEM;
            case EnvironmentEvent x -> Group.FACILITY;
            case CommunicationEvent x -> Group.COMMS;
            case AnomalyEvent x -> Group.INCIDENT;
            case OperatorEvent x -> Group.OPERATOR;
        };
    }

    /**
     * High-volume bookkeeping that would drown the log: individual steps, affect nudges,
     * sightings. Still recorded, still replayed, and visible when the filter is switched off.
     */
    public static boolean routine(SimEvent e) {
        return switch (e) {
            case EntityEvent.EntityMoved x -> true;
            case EntityEvent.AffectChanged x -> true;
            case EntityEvent.RelationshipChanged x -> true;
            case EntityEvent.EntityObserved x -> true;
            case EntityEvent.GoalChanged x -> true;
            case EntityEvent.MemoryFormed m -> m.trace().kind() == hauntedjvm.core.entity.MemoryKind.OBJECT_LOCATION
                    || m.trace().kind() == hauntedjvm.core.entity.MemoryKind.SAW_ENTITY && m.trace().valence() >= 0;
            case EntityEvent.MemoryFaded x -> true;
            case ProcessEvent.MemoryAllocated x -> true;
            case ProcessEvent.MemoryReleased x -> true;
            case CommunicationEvent.MessageReceived x -> true;
            case EnvironmentEvent.DoorOpened d -> d.by() != null;
            case EnvironmentEvent.DoorClosed x -> true;
            case AnomalyEvent.AnomalyResolved x -> true;
            default -> false;
        };
    }

    public static String describe(EventRecord r, WorldView names) {
        String text = describe(r.event(), names);
        if (r.misdated()) {
            text += "   [dated " + FacilityClock.format(r.reportedTick()) + "]";
        }
        return text;
    }

    public static String describe(SimEvent event, WorldView w) {
        return switch (event) {
            case EntityEvent.EntityCreated e -> e.entity().kind() + " " + e.entity().id() + " " + e.entity().name()
                    + " registered (" + e.origin() + ")";
            case EntityEvent.EntityMoved e -> n(w, e.id()) + " moved to " + (e.to() == null ? "?" : room(w, e.to()));
            case EntityEvent.EntityStateChanged e -> n(w, e.id()) + ": " + e.from() + " → " + e.to()
                    + (e.reason() == null ? "" : " (" + e.reason() + ")");
            case EntityEvent.GoalChanged e -> e.goal() == null ? n(w, e.id()) + " stopped"
                    : n(w, e.id()) + " heading to " + e.goal().roomCode() + " [" + e.goal().reason() + "]";
            case EntityEvent.AffectChanged e -> String.format("%s fear %+.2f awareness %+.2f corruption %+.3f (%s)",
                    n(w, e.id()), e.fear(), e.awareness(), e.corruption(), e.cause());
            case EntityEvent.EntityObserved e -> n(w, e.observer()) + " saw " + n(w, e.subject()) + " in " + e.roomCode();
            case EntityEvent.EntityForgotten e -> "nobody remembers " + n(w, e.id());
            case EntityEvent.EntityCorrupted e -> String.format("%s corruption past %.0f%%", n(w, e.id()), e.level() * 100);
            case EntityEvent.IdentityClaimed e -> e.id().equals(e.claimed()) ? n(w, e.id()) + " answers to their own name"
                    : n(w, e.id()) + " now answers to " + n(w, e.claimed());
            case EntityEvent.BadgeReported e -> e.reported() == null ? n(w, e.id()) + " badge tracking restored"
                    : n(w, e.id()) + " badge reports " + room(w, e.reported());
            case EntityEvent.TrackingChanged e -> n(w, e.id()) + (e.tracked() ? " now carries a badge" : " badge lost");
            case EntityEvent.RelationshipChanged e -> n(w, e.id()) + " regards " + n(w, e.other()) + " differently";
            case EntityEvent.MemoryFormed e -> n(w, e.id()) + " remembers: " + e.trace().note();
            case EntityEvent.MemoryFaded e -> n(w, e.id()) + " forgot a memory";
            case EntityEvent.MarkChanged e -> "(director) " + n(w, e.id()) + (e.present() ? " +" : " -") + e.mark();
            case ProcessEvent.ProcessStarted e -> n(w, e.process()) + " started (pid " + e.pid() + ")";
            case ProcessEvent.ProcessStopped e -> n(w, e.process()) + " stopped (pid " + e.pid() + ", " + e.reason() + ")";
            case ProcessEvent.MemoryAllocated e -> n(w, e.process()) + " allocated " + kb(e.bytes()) + " " + e.label();
            case ProcessEvent.MemoryReleased e -> n(w, e.process()) + " released " + kb(e.bytes());
            case ProcessEvent.PidClaimed e -> n(w, e.process()) + " reports pid " + e.claimedPid();
            case EnvironmentEvent.DoorOpened e -> n(w, e.door()) + " opened" + (e.by() == null ? " (nobody near)"
                    : " by " + n(w, e.by()));
            case EnvironmentEvent.DoorClosed e -> n(w, e.door()) + " closed";
            case EnvironmentEvent.DoorLockChanged e -> n(w, e.door()) + (e.locked() ? " locked" : " unlocked")
                    + " by " + e.actor();
            case EnvironmentEvent.DoorUnsealed e -> n(w, e.door()) + " is no longer sealed";
            case EnvironmentEvent.LightChanged e -> n(w, e.room()) + " lights " + e.mode() + " (" + e.cause() + ")";
            case EnvironmentEvent.CameraStatusChanged e -> n(w, e.camera()) + " " + e.status()
                    + (e.timelineOffset() > 0 ? " (" + e.timelineOffset() + "s behind)" : "");
            case EnvironmentEvent.ObjectDisplaced e -> n(w, e.object()) + " is now in " + room(w, e.to());
            case EnvironmentEvent.RoomRevealed e -> n(w, e.room()) + " added to the registry";
            case CommunicationEvent.MessageSent e -> (e.sender() == null ? "[NO SENDER]" : n(w, e.sender()))
                    + (e.recipient() == null ? "" : " → " + n(w, e.recipient()))
                    + " " + e.channel() + ": \"" + e.text() + "\"";
            case CommunicationEvent.MessageReceived e -> n(w, e.recipient()) + " received message " + e.messageSeq();
            case AnomalyEvent.AnomalyDetected e -> "ANOMALY " + e.anomaly().category() + "/" + e.anomaly().severity()
                    + ": " + e.anomaly().summary();
            case AnomalyEvent.AnomalyResolved e -> "anomaly resolved";
            case AnomalyEvent.AnomalyRetracted e -> "RECORD WITHDRAWN";
            case AnomalyEvent.AnomalyEscalated e -> "INCIDENT " + EscalationLevel.of(e.to()).label();
            case AnomalyEvent.PerturbationApplied e -> "(director) " + e.name() + (e.detail() == null ? "" : ": " + e.detail());
            case AnomalyEvent.PerturbationScheduled e -> "(director) scheduled " + e.step().perturbation() + " at "
                    + FacilityClock.format(e.step().dueTick());
            case OperatorEvent.CameraChanged e -> e.to() == null ? "operator left the cameras" : "operator switched to " + e.to();
            case OperatorEvent.DirectiveIssued e -> "operator intercom: " + switch (e.directive().type()) {
                case GATHER -> "all staff to " + e.directive().roomCode();
                case RETURN_TO_ROUTINE -> "return to stations";
            };
            case OperatorEvent.OperatorInspected e -> "operator inspected " + e.ref();
            case OperatorEvent.SimulationPaused e -> "recording paused";
            case OperatorEvent.SimulationResumed e -> "recording resumed";
            case OperatorEvent.TimelineRewound e -> "TIMELINE REWOUND from " + FacilityClock.format(e.fromTick()) + " to "
                    + FacilityClock.format(e.toTick()) + (e.residue().isEmpty() ? "" : "; " + e.residue().size()
                    + " memories did not reset");
        };
    }

    static String n(WorldView w, EntityId id) {
        if (id == null) {
            return "?";
        }
        Entity e = w.entity(id);
        return e == null ? id.toString() : e.name();
    }

    private static String room(WorldView w, hauntedjvm.core.world.Cell cell) {
        String code = w.map().roomCodeAt(cell);
        return code == null ? "a doorway" : code;
    }

    private static String kb(long bytes) {
        return bytes >= 1 << 20 ? String.format("%.1f MB", bytes / 1048576.0) : (bytes >> 10) + " KB";
    }
}
