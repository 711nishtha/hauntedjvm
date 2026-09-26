package hauntedjvm.core.anomaly;

import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.CameraStatus;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.event.AnomalyEvent;
import hauntedjvm.core.event.CommunicationEvent;
import hauntedjvm.core.event.CommunicationEvent.MessageSent;
import hauntedjvm.core.event.EntityEvent;
import hauntedjvm.core.event.EntityEvent.EntityObserved;
import hauntedjvm.core.event.EnvironmentEvent;
import hauntedjvm.core.event.EventLog;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.OperatorEvent;
import hauntedjvm.core.event.ProcessEvent;
import hauntedjvm.core.incident.AnomalyCategory;
import hauntedjvm.core.incident.DetectedAnomaly;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.time.FacilityClock;
import hauntedjvm.core.world.RoomKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds inconsistencies in the world and its recorded history.
 *
 * <p>The detector knows nothing about perturbations. It checks invariants a sane facility
 * would satisfy (records are dated when they are written, memories have causes, one badge per
 * person, dead processes stay dead) and reports violations. Some violations are caused by the
 * anomaly engine, some by rumours, some by the operator rewinding time; the detector cannot tell
 * the difference, and neither, at first, can the operator.
 */
public final class AnomalyDetector {

    private AnomalyDetector() {
    }

    /** Inspects events in {@code [fromSeq, toSeq)} plus the current state. */
    public static List<DetectedAnomaly> scan(WorldView w, EventLog log, long fromSeq, long toSeq) {
        List<DetectedAnomaly> found = new ArrayList<>();
        Map<String, EventRecord> transitions = new HashMap<>();
        for (EventRecord r : log.range(fromSeq, toSeq)) {
            inspectRecord(w, log, r, transitions, found);
        }
        inspectState(w, log, found);
        return found;
    }

    // ---- recorded history ------------------------------------------------------------------

    private static void inspectRecord(WorldView w, EventLog log, EventRecord r, Map<String, EventRecord> transitions,
                                      List<DetectedAnomaly> out) {
        long t = r.tick();
        if (r.misdated() && !r.event().internal()) {
            boolean future = r.reportedTick() > r.tick();
            String when = FacilityClock.format(r.reportedTick());
            out.add(new DetectedAnomaly("TEMPORAL:dated:" + r.seq(), AnomalyCategory.TEMPORAL, future ? 3 : 2, t,
                    r.event().subjects(), null,
                    future ? "record " + r.seq() + " is dated " + when + ", " + FacilityClock.duration(
                            r.reportedTick() - r.tick()) + " from now"
                            : r.reportedTick() < 0 ? "record " + r.seq() + " is dated " + when
                            + ", before this facility's records begin"
                            : "record " + r.seq() + " claims " + when + ", before it was written",
                    List.of(r.seq())));
        }
        switch (r.event()) {
            case ProcessEvent.MemoryAllocated e -> {
                Entity p = w.entity(e.process());
                if (p != null && p.facet() instanceof ProcessFacet pf && pf.terminated() && t > pf.terminatedTick()) {
                    out.add(new DetectedAnomaly("SYSTEM:zombie:" + pf.pid(), AnomalyCategory.SYSTEM, 3, t,
                            List.of(p.id()), null, "pid " + pf.pid() + " (" + pf.command() + ") terminated at "
                            + FacilityClock.format(pf.terminatedTick()) + " and is still allocating",
                            List.of(r.seq())));
                }
            }
            case MessageSent e -> message(w, r, e, out);
            case EnvironmentEvent.DoorOpened e when e.by() == null -> {
                String code = w.entity(e.door()) == null ? "a door" : w.entity(e.door()).name();
                out.add(new DetectedAnomaly("VISUAL:door:" + r.seq(), AnomalyCategory.VISUAL, 1, t, List.of(e.door()),
                        null, code + " opened with nobody near it", List.of(r.seq())));
            }
            case EnvironmentEvent.LightChanged e when "no fault logged".equals(e.cause()) -> {
                Entity room = w.entity(e.room());
                out.add(new DetectedAnomaly("VISUAL:light:" + r.seq(), AnomalyCategory.VISUAL, 1, t, List.of(e.room()),
                        room == null ? null : room.name(), "lights in " + (room == null ? "?" : room.name())
                        + " flickering; lumen-ctl reports no fault", List.of(r.seq())));
            }
            case EntityEvent.EntityCreated e when !isRoster(e.origin()) -> out.add(new DetectedAnomaly(
                    "IDENTITY:norecord:" + e.entity().id(), AnomalyCategory.IDENTITY, 3, t, List.of(e.entity().id()),
                    null, e.entity().kind() + " " + e.entity().id() + " has no personnel or asset record",
                    List.of(r.seq())));
            case EntityEvent.EntityStateChanged e -> {
                String key = e.id() + "@" + t;
                EventRecord first = transitions.putIfAbsent(key, r);
                if (first != null && first.event() instanceof EntityEvent.EntityStateChanged f
                        && f.from() == e.from() && f.to() != e.to()) {
                    out.add(new DetectedAnomaly("TEMPORAL:twice:" + key, AnomalyCategory.TEMPORAL, 4, t,
                            List.of(e.id()), null, name(w, e.id()) + " at " + FacilityClock.format(t)
                            + " recorded twice: " + f.to() + " and " + e.to(), List.of(first.seq(), r.seq())));
                }
            }
            case EntityEvent.MemoryFormed e -> memory(w, log, r, e, out);
            case OperatorEvent.TimelineRewound e -> {
                for (OperatorEvent.CarriedMemory c : e.residue()) {
                    out.add(new DetectedAnomaly("TEMPORAL:residue:" + c.owner() + ":" + c.trace().id(),
                            AnomalyCategory.TEMPORAL, 4, t, List.of(c.owner()), c.trace().roomCode(),
                            name(w, c.owner()) + " remembers \"" + c.trace().note() + "\" from "
                                    + FacilityClock.format(c.trace().tick()) + ", which has not happened",
                            List.of(r.seq())));
                }
            }
            default -> {
                // Everything else is internally consistent by construction.
            }
        }
    }

    private static void message(WorldView w, EventRecord r, MessageSent e, List<DetectedAnomaly> out) {
        long t = r.tick();
        if (e.sender() == null) {
            boolean log = e.channel() == CommunicationEvent.Channel.INCIDENT_LOG;
            out.add(new DetectedAnomaly(log ? "COMMUNICATION:orphan-log" : "COMMUNICATION:orphan:" + r.seq(),
                    AnomalyCategory.COMMUNICATION, 2, t, e.subjects(), null,
                    e.channel() + " message with no sender: \"" + e.text() + "\"", List.of(r.seq())));
            return;
        }
        Entity sender = w.entity(e.sender());
        if (sender != null && (sender.state() == EntityState.MISSING || sender.state() == EntityState.TERMINATED)) {
            out.add(new DetectedAnomaly("COMMUNICATION:dead-sender:" + r.seq(), AnomalyCategory.COMMUNICATION, 3, t,
                    List.of(sender.id()), null, "message from " + sender.name() + ", who is "
                    + sender.state().name().toLowerCase(), List.of(r.seq())));
        }
    }

    private static void memory(WorldView w, EventLog log, EventRecord r, EntityEvent.MemoryFormed e,
                               List<DetectedAnomaly> out) {
        Entity owner = w.entity(e.id());
        if (owner == null || !owner.hasMind()) {
            return;
        }
        MemoryTrace m = owner.mind().memory(r.seq()).orElse(null);
        if (m == null) {
            return;
        }
        long t = r.tick();
        String who = owner.name();
        EventRecord source = log.contains(m.sourceSeq()) ? log.get(m.sourceSeq()) : null;
        switch (m.kind()) {
            case SAW_ENTITY, OBJECT_LOCATION -> {
                Entity subject = w.entity(m.subject());
                if (m.subject() != null && subject == null) {
                    out.add(new DetectedAnomaly("IDENTITY:stranger:" + r.seq(), AnomalyCategory.IDENTITY, 3, t,
                            List.of(owner.id()), m.roomCode(), who + " remembers " + m.subject()
                            + ", who has never existed: \"" + m.note() + "\"", List.of(r.seq())));
                    return;
                }
                if (!(source != null && source.event() instanceof EntityObserved o && o.observer().equals(owner.id()))) {
                    out.add(fabricated(owner, m, r));
                    return;
                }
                Entity seen = w.entity(o.subject());
                if (subject != null && !o.subject().equals(m.subject()) && seen != null) {
                    out.add(new DetectedAnomaly("IDENTITY:mistaken:" + owner.id() + ":" + m.subject(),
                            AnomalyCategory.IDENTITY, 3, t, List.of(owner.id(), m.subject(), seen.id()), m.roomCode(),
                            who + " remembers seeing " + subject.name() + "; the record shows " + seen.id()
                                    + " (" + seen.name() + ")", List.of(r.seq(), source.seq())));
                }
                if (subject != null && subject.state() == EntityState.MISSING) {
                    out.add(new DetectedAnomaly("IDENTITY:after-missing:" + owner.id() + ":" + subject.id(),
                            AnomalyCategory.IDENTITY, 4, t, List.of(owner.id(), subject.id()), m.roomCode(),
                            who + " saw " + subject.name() + " in " + m.roomCode() + " at " + FacilityClock.format(t)
                                    + ". " + subject.name() + " is missing", List.of(r.seq(), source.seq())));
                }
            }
            case SAW_ANOMALY -> {
                if (source == null || Math.abs(source.tick() - m.tick()) > 1) {
                    out.add(fabricated(owner, m, r));
                }
            }
            case HEARD -> {
                if (!(source != null && source.event() instanceof MessageSent msg)
                        || (msg.recipient() != null && !msg.recipient().equals(owner.id()))) {
                    out.add(fabricated(owner, m, r));
                    return;
                }
                if (msg.claim() == null || msg.sender() == null || msg.claim().aboutMemory() < 0) {
                    return;
                }
                Entity speaker = w.entity(msg.sender());
                MemoryTrace original = speaker == null || !speaker.hasMind() ? null
                        : speaker.mind().memory(msg.claim().aboutMemory()).orElse(null);
                if (original != null && original.roomCode() != null
                        && !original.roomCode().equals(msg.claim().roomCode())) {
                    out.add(new DetectedAnomaly("MEMORY:retold:" + source.seq(), AnomalyCategory.MEMORY, 1, t,
                            List.of(speaker.id(), owner.id()), msg.claim().roomCode(), speaker.name() + " told " + who
                            + " it happened in " + msg.claim().roomCode() + "; " + speaker.name() + " remembers "
                            + original.roomCode(), List.of(source.seq(), r.seq())));
                }
            }
            default -> {
                // PREVIOUS_TIMELINE memories arrive through TimelineRewound and are reported there.
            }
        }
    }

    private static DetectedAnomaly fabricated(Entity owner, MemoryTrace m, EventRecord r) {
        return new DetectedAnomaly("MEMORY:fabricated:" + owner.id() + ":" + r.seq(), AnomalyCategory.MEMORY, 3,
                r.tick(), List.of(owner.id()), m.roomCode(), owner.name() + " remembers \"" + m.note()
                + "\" at " + FacilityClock.format(m.tick()) + ". No such event was recorded", List.of(r.seq()));
    }

    // ---- present state ---------------------------------------------------------------------

    private static void inspectState(WorldView w, EventLog log, List<DetectedAnomaly> out) {
        long t = w.tick();
        Map<EntityId, List<Entity>> byIdentity = new TreeMap<>();
        for (Entity e : w.entities()) {
            boolean counts = (e.kind() == EntityKind.PERSON || e.kind() == EntityKind.UNKNOWN)
                    && (e.present() || e.reportedPosition() != null);
            if (counts) {
                byIdentity.computeIfAbsent(e.identity(), k -> new ArrayList<>()).add(e);
            }
            switch (e.kind()) {
                case PERSON -> person(w, log, e, out);
                case UNKNOWN -> unknown(w, log, e, out);
                case CAMERA -> {
                    if (e.facet() instanceof CameraFacet cf && cf.status() == CameraStatus.LOOPING) {
                        out.add(new DetectedAnomaly("TEMPORAL:loop:" + cf.code(), AnomalyCategory.TEMPORAL, 2, t,
                                List.of(e.id()), cf.roomCode(), cf.code() + " is showing "
                                + FacilityClock.format(t - cf.timelineOffset()), evidence(log, e.id())));
                    }
                }
                case ROOM -> {
                    if (e.facet() instanceof RoomFacet rf && rf.listed()
                            && w.map().room(rf.code()).kind() == RoomKind.HIDDEN) {
                        out.add(new DetectedAnomaly("SPATIAL:unlisted:" + rf.code(), AnomalyCategory.SPATIAL, 5, t,
                                List.of(e.id()), rf.code(), rf.code() + " appears in the registry. "
                                + "It is not on the construction plan", evidence(log, e.id())));
                    }
                }
                default -> {
                    // no standing invariants for other kinds
                }
            }
        }
        for (Map.Entry<EntityId, List<Entity>> group : byIdentity.entrySet()) {
            if (group.getValue().size() > 1) {
                List<EntityId> ids = group.getValue().stream().map(Entity::id).toList();
                out.add(new DetectedAnomaly("IDENTITY:dup:" + group.getKey(), AnomalyCategory.IDENTITY, 4, t, ids, null,
                        ids.size() + " records answer to " + name(w, group.getKey()) + ": " + ids,
                        evidence(log, ids.getLast())));
            }
        }
        processes(w, log, out);
        observation(w, log, out);
    }

    private static void person(WorldView w, EventLog log, Entity p, List<DetectedAnomaly> out) {
        long t = w.tick();
        if (p.state() == EntityState.MISSING) {
            String last = p.reportedPosition() == null ? "nowhere" : w.map().roomCodeAt(p.reportedPosition());
            out.add(new DetectedAnomaly("SPATIAL:missing:" + p.id(), AnomalyCategory.SPATIAL, 3, t, List.of(p.id()),
                    last, p.name() + " is unaccounted for. Their badge still reports " + last, evidence(log, p.id())));
            return;
        }
        if (p.badgeOverride() && p.reportedPosition() != null && p.position() != null) {
            String reported = w.map().roomCodeAt(p.reportedPosition());
            String actual = w.roomOf(p);
            if (reported != null && !reported.equals(actual) && (covered(w, reported) || covered(w, actual))) {
                out.add(new DetectedAnomaly("SPATIAL:badge:" + p.id(), AnomalyCategory.SPATIAL, 2, t, List.of(p.id()),
                        reported, "tracker reports " + p.name() + " in " + reported + "; cameras disagree",
                        evidence(log, p.id())));
            }
        }
        if (p.state() == EntityState.ECHOING) {
            out.add(new DetectedAnomaly("BEHAVIORAL:echo:" + p.id(), AnomalyCategory.BEHAVIORAL, 2, t, List.of(p.id()),
                    w.roomOf(p), p.name() + " is retracing someone else's movements", evidence(log, p.id())));
        }
        if (p.state() == EntityState.CORRUPTED) {
            out.add(new DetectedAnomaly("BEHAVIORAL:corrupted:" + p.id(), AnomalyCategory.BEHAVIORAL, 3, t,
                    List.of(p.id()), w.roomOf(p), p.name() + " no longer follows any routine on record",
                    evidence(log, p.id())));
        }
        if (p.forgotten()) {
            out.add(new DetectedAnomaly("MEMORY:forgotten:" + p.id(), AnomalyCategory.MEMORY, 2, t, List.of(p.id()),
                    w.roomOf(p), "nobody on shift remembers " + p.name(), evidence(log, p.id())));
        }
    }

    private static void unknown(WorldView w, EventLog log, Entity u, List<DetectedAnomaly> out) {
        String room = w.roomOf(u);
        if (room != null && covered(w, room)) {
            out.add(new DetectedAnomaly("IDENTITY:unaccounted:" + u.id(), AnomalyCategory.IDENTITY, 3, w.tick(),
                    List.of(u.id()), room, "camera coverage of " + room + " shows a figure the tracker cannot see",
                    evidence(log, u.id())));
        }
    }

    private static void processes(WorldView w, EventLog log, List<DetectedAnomaly> out) {
        Map<Integer, List<Entity>> byPid = new TreeMap<>();
        for (Entity p : w.ofKind(EntityKind.PROCESS)) {
            if (p.facet() instanceof ProcessFacet pf) {
                byPid.computeIfAbsent(pf.claimedPid(), k -> new ArrayList<>()).add(p);
            }
        }
        for (Map.Entry<Integer, List<Entity>> g : byPid.entrySet()) {
            if (g.getValue().size() > 1) {
                List<EntityId> ids = g.getValue().stream().map(Entity::id).toList();
                String names = String.join(" and ", g.getValue().stream().map(Entity::name).toList());
                out.add(new DetectedAnomaly("SYSTEM:pid:" + g.getKey(), AnomalyCategory.SYSTEM, 3, w.tick(), ids, null,
                        names + " both report pid " + g.getKey(), evidence(log, ids.getFirst())));
            }
        }
    }

    private static void observation(WorldView w, EventLog log, List<DetectedAnomaly> out) {
        String room = w.observedRoom();
        String cam = w.incident().observedCamera();
        if (room == null) {
            return;
        }
        List<EntityId> held = new ArrayList<>();
        List<EntityId> facing = new ArrayList<>();
        for (Entity e : w.occupants(room)) {
            if (e.hasMark(Marks.OBSERVER_SENSITIVE)) {
                held.add(e.id());
            }
            if (e.state() == EntityState.AWARE && e.mind().goal() != null && e.position().equals(e.mind().goal().cell())) {
                facing.add(e.id());
            }
        }
        if (!held.isEmpty()) {
            out.add(new DetectedAnomaly("OBSERVATION:held:" + room, AnomalyCategory.OBSERVATION, 3, w.tick(), held, room,
                    held.size() + " in " + room + " stop moving while " + cam + " is active", evidence(log, held.getFirst())));
        }
        if (!facing.isEmpty()) {
            out.add(new DetectedAnomaly("OBSERVATION:facing:" + cam, AnomalyCategory.OBSERVATION, 4, w.tick(), facing,
                    room, facing.size() + " in " + room + " are standing still, facing " + cam,
                    evidence(log, facing.getFirst())));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static boolean covered(WorldView w, String room) {
        if (room == null) {
            return false;
        }
        for (Entity c : w.ofKind(EntityKind.CAMERA)) {
            if (c.facet() instanceof CameraFacet cf && cf.roomCode().equals(room) && cf.status() != CameraStatus.OFFLINE) {
                return true;
            }
        }
        return false;
    }

    /** The most recent non-internal record about an entity, looking back a bounded distance. */
    static List<Long> evidence(EventLog log, EntityId id) {
        long end = log.size();
        for (long s = end - 1; s >= Math.max(0, end - 4000); s--) {
            EventRecord r = log.get(s);
            if (!r.event().internal() && !(r.event() instanceof AnomalyEvent) && r.event().subjects().contains(id)) {
                return List.of(s);
            }
        }
        return List.of();
    }

    private static boolean isRoster(String origin) {
        return "ROSTER".equals(origin) || "SYSTEM".equals(origin);
    }

    private static String name(WorldView w, EntityId id) {
        Entity e = w.entity(id);
        return e == null ? String.valueOf(id) : e.name();
    }
}
