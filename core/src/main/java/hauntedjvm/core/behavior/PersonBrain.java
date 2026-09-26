package hauntedjvm.core.behavior;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Mind;
import hauntedjvm.core.entity.Relationship;
import hauntedjvm.core.entity.Traits;
import hauntedjvm.core.incident.Marks;
import hauntedjvm.core.state.WorldView;
import java.util.Optional;

/**
 * Chooses a person's behavioural state from their traits, affect, memories and company.
 *
 * <p>This is a utility-style decision with hysteresis rather than a scripted sequence: the same
 * fear level produces flight in one person and avoidance in a paranoid one, and a state, once
 * entered, needs a clear change of circumstances to be left. Thresholds are tuned so a calm
 * night stays calm; nothing here ever consults the clock or the escalation script directly.
 */
public final class PersonBrain {

    /** A decision plus a short explanation for the inspector and the event log. */
    public record Decision(EntityState state, String reason) {
    }

    private PersonBrain() {
    }

    public static Decision decide(WorldView w, Entity person) {
        long t = w.tick();
        Mind mind = person.mind();
        Traits traits = mind.traits();
        double fear = mind.fearAt(t);
        double awareness = mind.awarenessAt(t);
        double corruption = person.corruption();
        int level = w.incident().level();
        EntityState current = person.state();
        double salience = mind.mostSalientFear(t, 0.0).map(m -> -m.valence() * m.strengthAt(t,
                traits.memoryHalfLife())).orElse(0.0);

        if (corruption >= 0.72 || current == EntityState.CORRUPTED) {
            return new Decision(EntityState.CORRUPTED, String.format("corruption %.2f", corruption));
        }
        if ((awareness >= 0.7 && level >= 4) || awareness >= 0.9 || current == EntityState.AWARE) {
            return new Decision(EntityState.AWARE, String.format("awareness %.2f at level %d", awareness, level));
        }
        if (person.marks().stream().anyMatch(m -> m.startsWith(Marks.ECHO_PREFIX))) {
            return new Decision(EntityState.ECHOING, "moving like someone else");
        }
        boolean wasFrightened = current == EntityState.AFRAID || current == EntityState.AVOIDING;
        if (fear >= 0.62 || (wasFrightened && fear >= 0.45)) {
            EntityState s = traits.paranoia() > 0.5 ? EntityState.AVOIDING : EntityState.AFRAID;
            return new Decision(s, String.format("fear %.2f, paranoia %.2f", fear, traits.paranoia()));
        }
        Optional<MemoryTrace> lead = mind.mostSalientFear(t, 0.35);
        if (lead.isPresent() && t - lead.get().tick() < 500 && traits.curiosity() > fear * 0.9 + 0.1) {
            return new Decision(EntityState.INVESTIGATING, "curious about " + lead.get().roomCode());
        }
        if (current == EntityState.INVESTIGATING && mind.goal() != null && t < mind.goal().expiresAt()) {
            return new Decision(EntityState.INVESTIGATING, "still looking");
        }
        if (fear >= 0.3 && fear < 0.62 && traits.obedience() > 0.5 && leader(w, person).isPresent()) {
            return new Decision(EntityState.FOLLOWING, "sticking close to " + w.entity(leader(w, person).get().other()).name());
        }
        if (current == EntityState.FOLLOWING && fear >= 0.2 && leader(w, person).isPresent()) {
            return new Decision(EntityState.FOLLOWING, "still following");
        }
        if (fear >= 0.3 || salience > 0.2) {
            return new Decision(EntityState.SUSPICIOUS, String.format("uneasy (fear %.2f)", fear));
        }
        return new Decision(EntityState.NORMAL, "routine");
    }

    /**
     * The most trusted colleague in the same room who is steadier than this person, if any.
     * Followers inherit their leader's movements, including whatever the leader is avoiding.
     */
    public static Optional<Relationship> leader(WorldView w, Entity person) {
        String room = w.roomOf(person);
        if (room == null) {
            return Optional.empty();
        }
        long t = w.tick();
        double ownFear = person.mind().fearAt(t);
        Relationship best = null;
        for (Relationship r : person.mind().relationships()) {
            if (r.trust() < 0.6 || (best != null && r.trust() <= best.trust())) {
                continue;
            }
            Entity other = w.entity(r.other());
            if (other == null || !other.present() || !room.equals(w.roomOf(other)) || !other.hasMind()
                    || other.state() == EntityState.CORRUPTED || other.state() == EntityState.AWARE
                    || other.state() == EntityState.FOLLOWING || other.state() == EntityState.ECHOING) {
                continue;
            }
            if (other.mind().fearAt(t) <= ownFear) {
                best = r;
            }
        }
        return Optional.ofNullable(best);
    }
}
