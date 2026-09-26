package hauntedjvm.core.anomaly;

import hauntedjvm.core.behavior.Perception;
import hauntedjvm.core.behavior.Reactions;
import hauntedjvm.core.engine.TickContext;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.RoomFacet;
import hauntedjvm.core.event.AnomalyEvent.PerturbationApplied;
import hauntedjvm.core.event.AnomalyEvent.PerturbationScheduled;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.incident.ScheduledPerturbation;
import hauntedjvm.core.state.WorldView;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for perturbations: announcing, scheduling, witnessing, and picking targets. */
public final class Director {

    private Director() {
    }

    public static EventRecord announce(TickContext ctx, Perturbation p, List<EntityId> targets, String room,
                                       String detail) {
        return ctx.emit(new PerturbationApplied(p.name(), p.category(), targets, room, -1, detail));
    }

    public static void schedule(TickContext ctx, Perturbation p, long delay, List<EntityId> targets, String room,
                                String detail) {
        long id = ctx.log().nextSeq();
        ctx.emit(new PerturbationScheduled(new ScheduledPerturbation(id, ctx.tick() + Math.max(1, delay), p.name(),
                targets, room, detail)));
    }

    /** Everyone present in a room witnesses something, with the given intensity. */
    public static void witnesses(TickContext ctx, String room, long sourceSeq, double intensity, String note) {
        if (room == null) {
            return;
        }
        for (Entity e : ctx.world().occupants(room)) {
            if (e.kind() == EntityKind.PERSON) {
                Reactions.witnessAnomaly(ctx, e, sourceSeq, room, intensity, note);
            }
        }
    }

    public static List<Entity> presentPersons(WorldView w) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : w.ofKind(EntityKind.PERSON)) {
            if (e.present()) {
                out.add(e);
            }
        }
        return out;
    }

    public static List<String> listedRooms(WorldView w) {
        List<String> out = new ArrayList<>();
        for (Entity e : w.ofKind(EntityKind.ROOM)) {
            if (e.facet() instanceof RoomFacet rf && rf.listed()) {
                out.add(rf.code());
            }
        }
        return out;
    }

    /** Listed rooms that nobody is in and the operator is not watching. */
    public static List<String> unwitnessedRooms(WorldView w) {
        List<String> out = new ArrayList<>();
        String observed = w.observedRoom();
        for (String r : listedRooms(w)) {
            if (!r.equals(observed) && Perception.personsIn(w, r) == 0) {
                out.add(r);
            }
        }
        return out;
    }

    public static boolean unknownPresent(WorldView w) {
        return w.ofKind(EntityKind.UNKNOWN).stream().anyMatch(Entity::present);
    }
}
