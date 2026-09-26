package hauntedjvm.core.anomaly;

import hauntedjvm.core.entity.CameraFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.ProcessFacet;
import hauntedjvm.core.random.Rng;
import hauntedjvm.core.state.WorldView;
import hauntedjvm.core.world.DoorLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Text for messages that nobody sent.
 *
 * <p>Every line is assembled from the current world: real names, real rooms, the camera the
 * operator is actually watching, the number of times they have actually rewound. Nothing here
 * refers to anything outside the simulation, the operator's actions inside it being the only
 * exception, and those are recorded events like any other.
 */
final class MessageCorpus {

    /** A composed message; {@code prophecySubject} is set when the line predicts someone's movement. */
    record Line(String text, Entity prophecySubject, String prophecyRoom) {
    }

    private MessageCorpus() {
    }

    static Line compose(WorldView w, Rng rng) {
        List<Function<Rng, Line>> options = new ArrayList<>();
        List<Entity> persons = Director.presentPersons(w);
        List<String> rooms = Director.listedRooms(w);
        int level = w.incident().level();

        List<String> empty = Director.unwitnessedRooms(w);
        if (!empty.isEmpty()) {
            options.add(r -> plain(r.pick(empty) + " IS NOT EMPTY"));
        }
        if (!persons.isEmpty()) {
            options.add(r -> plain("COUNT AGAIN. THERE ARE " + (persons.size() + 1) + " OF YOU."));
            options.add(r -> {
                Entity p = r.pick(persons);
                String elsewhere = r.pick(rooms);
                return plain(p.name() + " IS STILL IN " + elsewhere);
            });
            options.add(r -> {
                Entity p = r.pick(persons);
                String room = r.pick(rooms);
                return new Line(p.name() + " WILL ENTER " + room, p, room);
            });
        }
        String cam = w.incident().observedCamera();
        if (cam != null) {
            options.add(r -> plain("WHO IS WATCHING " + cam));
        }
        List<Entity> processes = w.ofKind(EntityKind.PROCESS);
        if (!processes.isEmpty()) {
            options.add(r -> {
                ProcessFacet pf = (ProcessFacet) r.pick(processes).facet();
                return plain("PID " + pf.pid() + " DID NOT EXIT");
            });
        }
        if (level >= 3 && persons.size() >= 2) {
            options.add(r -> {
                Entity a = r.pick(persons);
                Entity b = r.pick(persons);
                return plain(a.name() + " DOES NOT REMEMBER " + b.name());
            });
            w.map().doors().stream().filter(DoorLayout::sealed).findFirst().ifPresent(d ->
                    options.add(r -> plain("THE ROOM BEHIND " + d.code() + " WAS ALWAYS THERE")));
        }
        if (level >= 4) {
            if (cam != null && w.camera(cam) != null && w.camera(cam).facet() instanceof CameraFacet cf) {
                options.add(r -> plain("OPERATOR. " + cf.roomCode() + " CAN SEE YOU BACK."));
            }
            int rewinds = w.incident().rewinds();
            if (rewinds > 0) {
                options.add(r -> plain("YOU HAVE REWOUND " + rewinds + (rewinds == 1 ? " TIME" : " TIMES")
                        + ". WE KEPT COUNT."));
            }
            String inspected = w.incident().lastInspected();
            Entity looked = inspectedPerson(w, inspected);
            if (looked != null) {
                options.add(r -> plain("STOP LOOKING AT " + looked.name() + "."));
            }
            long missing = w.ofKind(EntityKind.PERSON).stream().filter(e -> e.state() == EntityState.MISSING).count();
            if (missing > 0) {
                options.add(r -> plain(missing + " BADGE" + (missing == 1 ? "" : "S") + " STILL REPORTING. "
                        + "NOBODY WEARING " + (missing == 1 ? "IT" : "THEM") + "."));
            }
        }
        if (options.isEmpty()) {
            return plain("...");
        }
        return rng.pick(options).apply(rng);
    }

    private static Entity inspectedPerson(WorldView w, String ref) {
        if (ref == null || !ref.startsWith("entity:#")) {
            return null;
        }
        try {
            int serial = Integer.parseInt(ref.substring("entity:#".length()));
            Entity e = w.entity(hauntedjvm.core.entity.EntityId.of(serial));
            return e != null && e.kind() == EntityKind.PERSON ? e : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Line plain(String text) {
        return new Line(text, null, null);
    }
}
