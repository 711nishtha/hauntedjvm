package hauntedjvm.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FacilityMapTest {

    private final FacilityMap map = FacilityMap.facility07();

    @Test
    void loadsTheBundledPlan() {
        assertThat(map.width()).isEqualTo(64);
        assertThat(map.height()).isEqualTo(33);
        assertThat(map.rooms()).hasSize(16);
        assertThat(map.rooms().stream().filter(RoomLayout::listed)).hasSize(15);
        assertThat(map.cameras()).hasSize(9);
    }

    @Test
    void everyDoorJoinsTwoDistinctRooms() {
        for (DoorLayout d : map.doors()) {
            assertThat(d.roomA()).isNotEqualTo(d.roomB());
            assertThat(map.tile(d.cell())).isIn(Tile.DOOR, Tile.SEALED_DOOR);
        }
        assertThat(map.doors().stream().filter(DoorLayout::sealed)).hasSize(1);
    }

    @Test
    void everyListedRoomIsReachableFromEveryOther() {
        Navigator nav = new Navigator(map);
        long sealed = map.sealedMask();
        List<RoomLayout> listed = map.rooms().stream().filter(RoomLayout::listed).toList();
        for (RoomLayout a : listed) {
            for (RoomLayout b : listed) {
                assertThat(nav.reachable(a.centre(), b.centre(), sealed))
                        .as("%s -> %s", a.code(), b.code()).isTrue();
            }
        }
    }

    @Test
    void theHiddenRoomIsOnlyReachableOnceUnsealed() {
        Navigator nav = new Navigator(map);
        RoomLayout hidden = map.rooms().stream().filter(r -> !r.listed()).findFirst().orElseThrow();
        Cell corridor = map.room("CORRIDOR-N").centre();
        assertThat(nav.reachable(corridor, hidden.centre(), map.sealedMask())).isFalse();
        assertThat(nav.reachable(corridor, hidden.centre(), 0L)).isTrue();
    }

    @Test
    void terminalsAndAnchorsBelongToTheirRoom() {
        for (RoomLayout r : map.rooms()) {
            for (Cell c : r.anchors()) {
                assertThat(map.roomAt(c)).as("anchor %s of %s", c, r.code()).isSameAs(r);
            }
            for (Cell c : r.terminals()) {
                assertThat(map.tile(c)).isEqualTo(Tile.TERMINAL);
            }
        }
    }

    @Test
    void cameraMountsAreInsideTheirRooms() {
        for (CameraMount m : map.cameras()) {
            assertThat(map.roomCodeAt(m.mount())).isEqualTo(m.roomCode());
        }
    }

    @Test
    void theServiceShaftConnectsBothCorridorsWithoutADoor() {
        assertThat(map.adjacentRooms("SHAFT-C")).contains("CORRIDOR-N", "CORRIDOR-S");
    }

    @Test
    void rejectsUndeclaredGlyphs() {
        String text = "room A ROOM-01 ROOM X\nmap\n###\n#Q#\n###\n";
        assertThatThrownBy(() -> FacilityMap.load("bad", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("undeclared glyph");
    }
}
