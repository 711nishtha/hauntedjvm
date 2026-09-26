package hauntedjvm.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NavigatorTest {

    private final FacilityMap map = FacilityMap.facility07();

    @Test
    void walkingTheFieldReachesTheGoalInExactlyDistanceSteps() {
        Navigator nav = new Navigator(map);
        Cell from = map.room("ROOM-01").centre();
        Cell goal = map.room("ROOM-12").centre();
        int expected = nav.distance(from, goal, 0);
        assertThat(expected).isPositive();
        Cell at = from;
        int steps = 0;
        while (!at.equals(goal)) {
            Cell next = nav.nextStep(at, goal, 0);
            assertThat(next.manhattan(at)).isEqualTo(1);
            assertThat(map.passable(next, 0)).isTrue();
            at = next;
            steps++;
        }
        assertThat(steps).isEqualTo(expected);
    }

    @Test
    void lockedDoorsForceADetourOrBlockEntirely() {
        Navigator nav = new Navigator(map);
        DoorLayout labs = map.doors().stream()
                .filter(d -> d.connects("ROOM-07") && d.connects("ROOM-08")).findFirst().orElseThrow();
        Cell a = map.room("ROOM-07").centre();
        Cell b = map.room("ROOM-08").centre();
        int open = nav.distance(a, b, 0);
        int locked = nav.distance(a, b, 1L << labs.index());
        assertThat(locked).isGreaterThan(open);

        long everyDoorIntoStorage = 0;
        for (DoorLayout d : map.doors()) {
            if (d.connects("ROOM-11")) {
                everyDoorIntoStorage |= 1L << d.index();
            }
        }
        assertThat(nav.reachable(map.room("CORRIDOR-S").centre(), map.room("ROOM-11").centre(), everyDoorIntoStorage))
                .isFalse();
    }

    @Test
    void cachingNeverChangesAnswers() {
        Navigator warm = new Navigator(map);
        Cell goal = map.room("ROOM-04").centre();
        for (RoomLayout r : map.rooms()) {
            warm.distance(r.centre(), goal, map.sealedMask());
        }
        Navigator cold = new Navigator(map);
        for (RoomLayout r : map.rooms()) {
            assertThat(warm.nextStep(r.centre(), goal, map.sealedMask()))
                    .isEqualTo(cold.nextStep(r.centre(), goal, map.sealedMask()));
        }
        assertThat(warm.fieldsComputed()).isEqualTo(1);
    }
}
