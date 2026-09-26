package hauntedjvm.core.state;

import static org.assertj.core.api.Assertions.assertThat;

import hauntedjvm.core.entity.DoorFacet;
import hauntedjvm.core.entity.Entity;
import hauntedjvm.core.entity.EntityKind;
import hauntedjvm.core.entity.EntityState;
import hauntedjvm.core.entity.MemoryKind;
import hauntedjvm.core.entity.MemoryTrace;
import hauntedjvm.core.event.EntityEvent.BadgeReported;
import hauntedjvm.core.event.EntityEvent.EntityForgotten;
import hauntedjvm.core.event.EntityEvent.EntityMoved;
import hauntedjvm.core.event.EntityEvent.EntityStateChanged;
import hauntedjvm.core.event.EntityEvent.MemoryFormed;
import hauntedjvm.core.event.EnvironmentEvent.DoorLockChanged;
import hauntedjvm.core.event.EventRecord;
import hauntedjvm.core.event.OperatorEvent.CarriedMemory;
import hauntedjvm.core.event.OperatorEvent.TimelineRewound;
import hauntedjvm.core.support.TestWorld;
import hauntedjvm.core.world.Cell;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldStateTest {

    @Test
    void genesisPopulatesTheFacility() {
        TestWorld w = TestWorld.genesis(1);
        assertThat(w.persons()).hasSize(16);
        assertThat(w.state().ofKind(EntityKind.PROCESS)).hasSize(8);
        assertThat(w.state().ofKind(EntityKind.CAMERA)).hasSize(8);
        assertThat(w.state().system()).isNotNull();
        assertThat(w.state().process("badge-trackd")).isNotNull();
        assertThat(w.persons()).allSatisfy(p -> {
            assertThat(p.present()).isTrue();
            assertThat(p.reportedPosition()).isEqualTo(p.position());
        });
    }

    @Test
    void movingUpdatesRoomOccupancyAndTheBadge() {
        TestWorld w = TestWorld.genesis(2);
        Entity p = w.person(0);
        Cell target = w.state().map().room("ROOM-04").centre();
        w.emit(new EntityMoved(p.id(), p.position(), target));
        Entity moved = w.refresh(p);
        assertThat(moved.position()).isEqualTo(target);
        assertThat(moved.reportedPosition()).isEqualTo(target);
        assertThat(w.state().occupants("ROOM-04")).extracting(Entity::id).contains(p.id());
    }

    @Test
    void aPinnedBadgeIgnoresMovementUntilReleased() {
        TestWorld w = TestWorld.genesis(3);
        Entity p = w.person(0);
        Cell ghost = w.state().map().room("ROOM-12").centre();
        w.emit(new BadgeReported(p.id(), ghost));
        Cell next = w.state().map().room("ROOM-04").centre();
        w.emit(new EntityMoved(p.id(), p.position(), next));
        assertThat(w.refresh(p).reportedPosition()).isEqualTo(ghost);
        w.emit(new BadgeReported(p.id(), null));
        assertThat(w.refresh(p).reportedPosition()).isEqualTo(next);
    }

    @Test
    void goingMissingRemovesTheBodyButLeavesTheBadge() {
        TestWorld w = TestWorld.genesis(4);
        Entity p = w.person(1);
        String room = w.state().roomOf(p);
        w.emit(new EntityStateChanged(p.id(), p.state(), EntityState.MISSING, "test"));
        Entity missing = w.refresh(p);
        assertThat(missing.position()).isNull();
        assertThat(missing.reportedPosition()).isEqualTo(p.position());
        assertThat(w.state().occupants(room)).extracting(Entity::id).doesNotContain(p.id());
    }

    @Test
    void memoriesTakeTheirIdFromTheLogAndRestoreRemembrance() {
        TestWorld w = TestWorld.genesis(5);
        Entity a = w.person(0);
        Entity b = w.person(1);
        w.emit(new EntityForgotten(b.id()));
        assertThat(w.refresh(b).forgotten()).isTrue();
        EventRecord r = w.emit(new MemoryFormed(a.id(), new MemoryTrace(0, 0, MemoryKind.SAW_ENTITY, b.id(), "ROOM-02",
                null, 0.5, 0.1, -1, "saw b")));
        assertThat(w.refresh(a).mind().memory(r.seq())).isPresent();
        assertThat(w.refresh(b).forgotten()).isFalse();
    }

    @Test
    void lockingADoorUpdatesTheNavigationMask() {
        TestWorld w = TestWorld.genesis(6);
        Entity door = w.state().ofKind(EntityKind.DEVICE).stream()
                .filter(e -> e.facet() instanceof DoorFacet d && !d.sealed() && !d.locked()).findFirst().orElseThrow();
        int index = ((DoorFacet) door.facet()).index();
        assertThat(w.state().blockedDoors() & (1L << index)).isZero();
        w.emit(new DoorLockChanged(door.id(), true, "test"));
        assertThat(w.state().blockedDoors() & (1L << index)).isNotZero();
    }

    @Test
    void rewindResidueBecomesPreviousTimelineMemories() {
        TestWorld w = TestWorld.genesis(7);
        Entity a = w.person(0);
        MemoryTrace future = new MemoryTrace(42, 900, MemoryKind.SAW_ANOMALY, null, "ROOM-04", null, 0.9, -0.9, 10,
                "the lights went out");
        w.emit(new TimelineRewound(1000, 0, List.of(new CarriedMemory(a.id(), future))));
        MemoryTrace carried = w.refresh(a).mind().memories().getLast();
        assertThat(carried.kind()).isEqualTo(MemoryKind.PREVIOUS_TIMELINE);
        assertThat(carried.id()).isNegative();
        assertThat(carried.tick()).isEqualTo(900);
        assertThat(w.state().incident().rewinds()).isEqualTo(1);
    }

    @Test
    void snapshotRestoreRoundTripsExactly() {
        TestWorld w = TestWorld.genesis(8);
        WorldSnapshot snap = w.state().snapshot();
        WorldState restored = WorldState.restore(w.state().map(), snap);
        assertThat(restored.snapshot()).isEqualTo(snap);
        assertThat(restored.blockedDoors()).isEqualTo(w.state().blockedDoors());
    }
}
