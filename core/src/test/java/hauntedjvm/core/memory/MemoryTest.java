package hauntedjvm.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.event.CommunicationEvent.MessageSent;
import hauntedjvm.core.event.EntityEvent.EntityMoved;
import hauntedjvm.core.event.EntityEvent.MemoryFormed;
import hauntedjvm.core.event.EntityEvent.RelationshipChanged;
import hauntedjvm.core.support.TestWorld;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryTest {

    @Test
    void faintMemoriesAreDropped() {
        TestWorld w = TestWorld.genesis(3);
        Entity p = w.person(0);
        MemoryFormed formed = new MemoryFormed(p.id(), new MemoryTrace(0, 0, MemoryKind.SAW_ENTITY, null, "ROOM-02",
                null, 0.3, 0.1, -1, "faint"));
        long id = w.emit(formed).seq();
        MemorySystem system = new MemorySystem();
        long halfLife = (long) p.mind().traits().memoryHalfLife();
        // Tick on the entity's fade phase, long after the memory should have faded.
        long t = halfLife * 6;
        t += Math.floorMod(-(t + p.id().value()), 20);
        w.advanceTo(t);
        system.tick(w);
        assertThat(w.refresh(p).mind().memory(id)).isEmpty();
    }

    @Test
    void someoneNobodyRemembersIsMarkedForgotten() {
        TestWorld w = TestWorld.genesis(4);
        Entity loner = w.person(0);
        w.advanceTo(1_000);
        new MemorySystem().tick(w);
        assertThat(w.refresh(loner).forgotten()).isTrue();

        Entity friend = w.person(1);
        w.emit(new MemoryFormed(friend.id(), new MemoryTrace(0, 1_000, MemoryKind.SAW_ENTITY, loner.id(), "ROOM-02",
                null, 0.8, 0.3, -1, "saw them")));
        assertThat(w.refresh(loner).forgotten()).isFalse();
    }

    @Test
    void worriesSpreadAsHearsayWeightedByTrust() {
        TestWorld w = TestWorld.genesis(21);
        List<Entity> persons = w.persons();
        Entity speaker = persons.get(0);
        Entity listener = persons.get(1);
        w.emit(new EntityMoved(listener.id(), listener.position(), speaker.position()));
        for (Entity other : persons.subList(2, persons.size())) {
            // Clear the room so the listener is the only possible audience.
            w.emit(new EntityMoved(other.id(), other.position(), w.state().map().room("ROOM-12").centre()));
        }
        w.emit(new RelationshipChanged(listener.id(), speaker.id(), 0.4, 0.3));
        w.emit(new MemoryFormed(speaker.id(), new MemoryTrace(0, 0, MemoryKind.SAW_ANOMALY, null, "ROOM-04", null, 1.0,
                -1.0, -1, "the archive")));

        SocialSystem social = new SocialSystem();
        for (long t = 1; t < 2_000 && heard(w, listener) == null; t++) {
            w.advanceTo(t);
            social.tick(w);
        }
        MemoryTrace heard = heard(w, listener);
        assertThat(heard).isNotNull();
        assertThat(heard.valence()).isNegative();
        assertThat(w.log().get(heard.sourceSeq()).event()).isInstanceOf(MessageSent.class);
    }

    private static MemoryTrace heard(TestWorld w, Entity listener) {
        return w.refresh(listener).mind().memories().stream().filter(m -> m.kind() == MemoryKind.HEARD).findFirst()
                .orElse(null);
    }

    @Test
    void onlyPersonsCanBeForgotten() {
        TestWorld w = TestWorld.genesis(5);
        w.advanceTo(1_000);
        new MemorySystem().tick(w);
        assertThat(w.state().ofKind(EntityKind.PROCESS)).noneMatch(Entity::forgotten);
    }
}
