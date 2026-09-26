package hauntedjvm.core.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.Goal;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.entity.Role;
import hauntedjvm.core.event.EntityEvent.AffectChanged;
import hauntedjvm.core.event.EntityEvent.EntityMoved;
import hauntedjvm.core.event.EntityEvent.MemoryFormed;
import hauntedjvm.core.event.EntityEvent.RelationshipChanged;
import hauntedjvm.core.support.TestWorld;
import org.junit.jupiter.api.Test;

class BehaviorTest {

    private static Entity firstWithRole(TestWorld w, Role role) {
        return w.persons().stream().filter(p -> p.mind().role() == role).findFirst().orElseThrow();
    }

    private static int visitsTo(TestWorld w, Entity p, String room, int samples) {
        int hits = 0;
        for (int i = 0; i < samples; i++) {
            w.advanceTo(w.state().tick() + 1);
            Goal g = GoalPlanner.plan(w, w.refresh(p));
            if (g != null && room.equals(g.roomCode())) {
                hits++;
            }
        }
        return hits;
    }

    /** The core emergent loop: one bad memory reshapes where someone goes, without any rule naming the room. */
    @Test
    void aFrighteningMemoryKeepsAPersonOutOfARoom() {
        TestWorld calm = TestWorld.genesis(31);
        Entity archivist = firstWithRole(calm, Role.ARCHIVIST);
        int before = visitsTo(calm, archivist, "ROOM-04", 400);

        TestWorld scared = TestWorld.genesis(31);
        scared.emit(new MemoryFormed(archivist.id(), new MemoryTrace(0, 0, MemoryKind.SAW_ANOMALY, null, "ROOM-04",
                null, 1.0, -1.0, -1, "something in the archive")));
        int after = visitsTo(scared, archivist, "ROOM-04", 400);

        assertThat(before).isGreaterThan(60);
        assertThat(after).isLessThan(before / 2);
    }

    @Test
    void fearTurnsIntoFlightOrAvoidanceDependingOnParanoia() {
        TestWorld w = TestWorld.genesis(8);
        for (Entity p : w.persons()) {
            w.emit(new AffectChanged(p.id(), 1.0, 0, 0, "test"));
        }
        for (Entity p : w.persons()) {
            Entity now = w.refresh(p);
            EntityState expected = now.mind().traits().paranoia() > 0.5 ? EntityState.AVOIDING : EntityState.AFRAID;
            assertThat(PersonBrain.decide(w.state(), now).state()).isEqualTo(expected);
        }
    }

    @Test
    void heavyCorruptionOverridesEverythingElse() {
        TestWorld w = TestWorld.genesis(9);
        Entity p = w.person(0);
        w.emit(new AffectChanged(p.id(), 1.0, 1.0, 0.9, "test"));
        assertThat(PersonBrain.decide(w.state(), w.refresh(p)).state()).isEqualTo(EntityState.CORRUPTED);
    }

    @Test
    void anUneasyObedientPersonFollowsATrustedCalmColleague() {
        TestWorld w = TestWorld.genesis(12);
        Entity follower = w.persons().stream().filter(p -> p.mind().traits().obedience() > 0.5).findFirst()
                .orElseThrow();
        Entity leader = w.persons().stream().filter(p -> !p.id().equals(follower.id())).findFirst().orElseThrow();
        w.emit(new EntityMoved(leader.id(), leader.position(), follower.position()));
        w.emit(new RelationshipChanged(follower.id(), leader.id(), 0.5, 0.2));
        w.emit(new AffectChanged(follower.id(), 0.4, 0, 0, "test"));

        PersonBrain.Decision decision = PersonBrain.decide(w.state(), w.refresh(follower));

        assertThat(decision.state()).isEqualTo(EntityState.FOLLOWING);
        assertThat(PersonBrain.leader(w.state(), w.refresh(follower)).orElseThrow().other()).isEqualTo(leader.id());
    }

    @Test
    void paceDependsOnState() {
        assertThat(Locomotion.pace(EntityState.AFRAID)).isLessThan(Locomotion.pace(EntityState.NORMAL));
        assertThat(Locomotion.pace(EntityState.AWARE)).isGreaterThan(Locomotion.pace(EntityState.NORMAL));
    }

    @Test
    void flickerIsAPureFunctionOfRoomAndTime() {
        for (long t = 0; t < 100; t++) {
            assertThat(Perception.flickerDark("ROOM-04", t)).isEqualTo(Perception.flickerDark("ROOM-04", t));
        }
        long darkTicks = java.util.stream.LongStream.range(0, 3000).filter(t -> Perception.flickerDark("ROOM-04", t))
                .count();
        assertThat(darkTicks).isBetween(800L, 1200L);
    }
}
